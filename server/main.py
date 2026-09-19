import asyncio
import hashlib
import json
import math
import os
import re
import sqlite3
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Dict, List, Optional

import feedparser
import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

try:
    import firebase_admin
    from firebase_admin import credentials, messaging
except Exception:
    firebase_admin = None
    messaging = None

BINANCE_FAPI = "https://fapi.binance.com"
BINANCE_CMS = [
    "https://www.binance.com/bapi/composite/v1/public/cms/article/catalog/list/query?catalogId=161&pageNo=1&pageSize=50",
    "https://www.binance.com/bapi/composite/v1/public/cms/article/catalog/list/query?catalogId=48&pageNo=1&pageSize=50",
]
DEFAULT_RSS = [
    "https://www.coindesk.com/arc/outboundfeeds/rss/",
    "https://cointelegraph.com/rss",
]
NEGATIVE_TERMS = {
    "delist": 24, "delisting": 24, "remove": 12, "removal": 12,
    "suspend": 18, "suspension": 18, "exploit": 24, "hack": 24,
    "breach": 20, "lawsuit": 14, "investigation": 14, "bankrupt": 24,
    "insolvency": 24, "shutdown": 18, "outage": 10, "liquidation": 12,
    "unlock": 6, "dump": 8, "rug": 28, "fraud": 24, "warning": 10,
}
EXCLUDED = {"BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT","DOGEUSDT","ADAUSDT","TRXUSDT","AVAXUSDT","LINKUSDT","BCHUSDT","LTCUSDT","DOTUSDT","TONUSDT"}
DB = os.getenv("DB_PATH", "scanner.db")
SCAN_INTERVAL = max(60, int(os.getenv("SCAN_INTERVAL_SECONDS", "60")))
ALERT_THRESHOLD = float(os.getenv("ALERT_SCORE_THRESHOLD", "80"))
COOLDOWN = max(1, int(os.getenv("ALERT_COOLDOWN_MINUTES", "15"))) * 60
API_KEY = os.getenv("BACKEND_API_KEY", "").strip()

def require_key(x_scanner_key: Optional[str]):
    if API_KEY and x_scanner_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid scanner key")

class Device(BaseModel):
    token: str
    platform: str = "android"


def db_conn():
    c = sqlite3.connect(DB)
    c.execute("CREATE TABLE IF NOT EXISTS devices(token TEXT PRIMARY KEY, platform TEXT, updated_at INTEGER)")
    c.execute("CREATE TABLE IF NOT EXISTS alerts(symbol TEXT PRIMARY KEY, fingerprint TEXT, sent_at INTEGER)")
    c.commit()
    return c


def init_firebase():
    if not firebase_admin or not messaging:
        return False
    try:
        if firebase_admin._apps:
            return True
        path = os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
        if not path or not os.path.exists(path):
            return False
        firebase_admin.initialize_app(credentials.Certificate(path))
        return True
    except Exception as e:
        print("Firebase init skipped:", e)
        return False


def derive_action(c):
    if c.get("extended") and c["score"] >= 55:
        c["action"] = "WAIT"
    elif c["score"] >= 75 and c["ret1"] < 0 and c["lowerCount"] >= 3:
        c["action"] = "ENTER WATCH"
    elif c["score"] >= 50:
        c["action"] = "WAIT"
    else:
        c["action"] = "ABORT"


def score_candidate(c, rows):
    close=[float(x[4]) for x in rows]; high=[float(x[2]) for x in rows]; low=[float(x[3]) for x in rows]; vol=[float(x[5]) for x in rows]
    n=len(rows)
    if n < 25: return None
    c["price"]=close[-1]
    c["ret1"]=(close[-1]/close[-2]-1)*100
    c["ret5"]=(close[-1]/close[-6]-1)*100
    c["ret15"]=(close[-1]/close[-16]-1)*100
    recent=sum(vol[-5:])/5; base=sum(vol[-25:-5])/20
    c["volRatio"]=recent/max(base,1e-12)
    c["lowerCount"]=sum(1 for i in range(n-5,n) if high[i]<high[i-1] and low[i]<low[i-1])
    score=0.0
    ch=c["change24"]
    score += min(28,-ch*0.85) if ch<0 else -min(12,ch*0.25)
    score += min(10,-c["ret1"]*8) if c["ret1"]<0 else -min(8,c["ret1"]*5)
    score += min(24,-c["ret5"]*5.5) if c["ret5"]<0 else -min(10,c["ret5"]*3)
    if c["ret15"]<0: score += min(14,-c["ret15"]*2.2)
    score += min(12,max(0,c["volRatio"]-1)*7)
    score += c["lowerCount"]*3
    c["extended"] = c["ret5"] < -8 or c["ret15"] < -18
    if c["ret5"] < -8: score -= 14
    if c["ret15"] < -18: score -= 8
    c["score"]=max(0,min(100,score))
    derive_action(c)
    return c


def negative_weight(text: str) -> int:
    low=text.lower(); return min(35,sum(w for term,w in NEGATIVE_TERMS.items() if term in low))


def base_symbol(sym: str) -> str:
    return sym[:-4] if sym.endswith("USDT") else sym


def mentions_token(text: str, token: str) -> bool:
    # Avoid matching tiny ticker substrings inside words.
    return re.search(rf"(?<![A-Z0-9]){re.escape(token.upper())}(?![A-Z0-9])", text.upper()) is not None


async def fetch_catalysts(client: httpx.AsyncClient, tokens: List[str]) -> Dict[str, dict]:
    out: Dict[str,dict] = {}
    async def consider(title, url, source):
        w=negative_weight(title)
        if w<=0: return
        for t in tokens:
            if mentions_token(title,t):
                prev=out.get(t)
                item={"title":title[:220],"url":url,"source":source,"weight":w}
                if not prev or item["weight"]>prev["weight"]: out[t]=item

    # Binance exchange announcements
    for url in BINANCE_CMS:
        try:
            r=await client.get(url,timeout=10); r.raise_for_status(); data=r.json().get("data",{})
            articles=data.get("articles") or []
            for catalog in data.get("catalogs") or []: articles += catalog.get("articles") or []
            for a in articles:
                await consider(a.get("title", ""), a.get("code", ""), "Binance")
        except Exception as e: print("Binance catalyst source:",e)

    # Public RSS feeds
    urls=[u.strip() for u in os.getenv("NEWS_RSS_URLS","").split(",") if u.strip()] or DEFAULT_RSS
    for url in urls:
        try:
            r=await client.get(url,timeout=12,headers={"User-Agent":"CryptoShortScanner/3.0"}); r.raise_for_status()
            feed=feedparser.loads(r.text)
            for ent in feed.entries[:60]:
                title=getattr(ent,"title","")
                await consider(title,getattr(ent,"link",url),feed.feed.get("title","RSS"))
        except Exception as e: print("RSS catalyst source:",url,e)

    # Optional CryptoPanic API token for broader structured crypto news.
    cp=os.getenv("CRYPTOPANIC_AUTH_TOKEN","").strip()
    if cp:
        try:
            r=await client.get("https://cryptopanic.com/api/developer/v2/posts/",params={"auth_token":cp,"public":"true"},timeout=12)
            if r.is_success:
                for ent in r.json().get("results",[])[:100]:
                    await consider(ent.get("title",""),ent.get("url",""),"CryptoPanic")
        except Exception as e: print("CryptoPanic catalyst source:",e)
    return out


async def scan_market() -> List[dict]:
    async with httpx.AsyncClient(headers={"User-Agent":"CryptoShortScanner/3.0"}) as client:
        r=await client.get(BINANCE_FAPI+"/fapi/v1/ticker/24hr",timeout=12); r.raise_for_status()
        allc=[]
        for o in r.json():
            s=o.get("symbol","")
            if not s.endswith("USDT") or s in EXCLUDED: continue
            q=float(o.get("quoteVolume") or 0); ch=float(o.get("priceChangePercent") or 0)
            if q<3_000_000: continue
            pre=min(35,abs(ch))*1.25 + math.log10(max(q,1))*2
            allc.append({"symbol":s,"change24":ch,"quoteVolume":q,"price":float(o.get("lastPrice") or 0),"preScore":pre})
        allc.sort(key=lambda x:x["preScore"],reverse=True)
        shortlist=allc[:40]

        sem=asyncio.Semaphore(10)
        async def one(c):
            async with sem:
                try:
                    k=await client.get(BINANCE_FAPI+"/fapi/v1/klines",params={"symbol":c["symbol"],"interval":"1m","limit":40},timeout=10)
                    k.raise_for_status(); return score_candidate(c,k.json())
                except Exception as e: return None
        scored=[x for x in await asyncio.gather(*(one(c) for c in shortlist)) if x]
        cats=await fetch_catalysts(client,[base_symbol(c["symbol"]) for c in scored])
        for c in scored:
            cat=cats.get(base_symbol(c["symbol"]))
            if cat:
                c["catalyst"]=cat
                c["score"]=min(100,c["score"]+cat["weight"])
                derive_action(c)
        scored.sort(key=lambda x:x["score"],reverse=True)
        return scored[:10]


def should_send(c):
    if c["score"] < ALERT_THRESHOLD or c["action"] != "ENTER WATCH": return False
    cat=(c.get("catalyst") or {}).get("title","")
    fp=hashlib.sha256(f"{c['symbol']}|{round(c['score'])}|{cat}".encode()).hexdigest()[:16]
    now=int(time.time())
    con=db_conn(); row=con.execute("SELECT fingerprint,sent_at FROM alerts WHERE symbol=?",(c["symbol"],)).fetchone()
    ok=not row or row[0]!=fp or now-row[1]>=COOLDOWN
    if ok:
        con.execute("INSERT OR REPLACE INTO alerts(symbol,fingerprint,sent_at) VALUES(?,?,?)",(c["symbol"],fp,now)); con.commit()
    con.close(); return ok


def push(c):
    if not init_firebase(): return 0
    con=db_conn(); tokens=[r[0] for r in con.execute("SELECT token FROM devices").fetchall()]; con.close()
    cat=(c.get("catalyst") or {}).get("title","")
    data={
        "symbol":c["symbol"], "score":str(int(round(c["score"]))), "action":c["action"],
        "price":str(c["price"]), "ret1":f"{c['ret1']:.2f}", "ret5":f"{c['ret5']:.2f}",
        "catalyst":cat[:180]
    }
    sent=0
    for token in tokens:
        try:
            messaging.send(messaging.Message(data=data,token=token,android=messaging.AndroidConfig(priority="high")))
            sent+=1
        except Exception as e:
            print("Push failed:",str(e)[:180])
    return sent


async def run_scan_and_alert():
    results=await scan_market()
    for c in results:
        if should_send(c):
            push(c)
    return results


async def scheduler():
    await asyncio.sleep(3)
    while True:
        try:
            results=await run_scan_and_alert()
            if results: print(datetime.now(timezone.utc).isoformat(),"top",results[0]["symbol"],round(results[0]["score"]))
        except Exception as e: print("Scheduled scan error:",e)
        await asyncio.sleep(SCAN_INTERVAL)


@asynccontextmanager
async def lifespan(app: FastAPI):
    db_conn().close(); init_firebase()
    task=asyncio.create_task(scheduler())
    yield
    task.cancel()

app=FastAPI(title="Crypto Short Scanner Backend",version="3.0.0",lifespan=lifespan)

@app.get("/health")
def health():
    return {"ok":True,"version":"3.0.0","scan_interval_seconds":SCAN_INTERVAL,"firebase":init_firebase()}

@app.post("/devices/register")
def register(d: Device, x_scanner_key: Optional[str] = Header(default=None)):
    require_key(x_scanner_key)
    token=d.token.strip()
    if len(token)<20: return {"ok":False,"error":"invalid token"}
    con=db_conn(); con.execute("INSERT OR REPLACE INTO devices(token,platform,updated_at) VALUES(?,?,?)",(token,d.platform,int(time.time()))); con.commit(); con.close()
    return {"ok":True}

@app.post("/scan-now")
async def scan_now(x_scanner_key: Optional[str] = Header(default=None)):
    require_key(x_scanner_key)
    results=await run_scan_and_alert()
    return {"ok":True,"results":results}
