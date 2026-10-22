package com.investory.marketdata;

import java.util.List;

/** Stable identities shared by provider adapters and page projection. */
public final class WorldMarketCatalog {
    private WorldMarketCatalog() {}
    public enum Source { SINA, YAHOO }
    public record Spec(Source source, String fetchSymbol, String name, String flag,
                       double lat, double lng, String dbSymbol, boolean indicator) {
        public boolean supportsHithink() { return "CN".equals(flag); }
    }
    public static final List<Spec> SPECS = List.of(
        // ── Country indices ──────────────────────────────────────────
        new Spec(Source.SINA,  "s_sh000001", "上证指数",   "CN", 31.23, 121.47, "000001.SH", false),
        new Spec(Source.SINA,  "s_sz399001", "深证成指",   "CN", 31.23, 121.47, "399001.SZ", false),
        new Spec(Source.SINA,  "s_sz399006", "创业板指",   "CN", 31.23, 121.47, "399006.SZ", false),
        new Spec(Source.YAHOO, "^HSI",       "恒生指数",   "HK", 22.30, 114.17, "HSI.HK",    false),
        new Spec(Source.YAHOO, "^HSCE",      "国企指数",   "HK", 22.30, 114.17, "HSCE.HK",   false),
        new Spec(Source.YAHOO, "HSTECH.HK",  "恒生科技",   "HK", 22.30, 114.17, "HSTECH.HK", false),
        new Spec(Source.YAHOO, "^GSPC",      "标普500",    "US", 40.71, -74.01, "GSPC.US",   false),
        new Spec(Source.YAHOO, "^DJI",       "道琼斯",     "US", 40.71, -74.01, "DJI.US",    false),
        new Spec(Source.YAHOO, "^IXIC",      "纳斯达克",   "US", 40.71, -74.01, "IXIC.US",   false),
        new Spec(Source.YAHOO, "^N225",      "日经225",    "JP", 35.68, 139.76, "N225.JP",   false),
        new Spec(Source.YAHOO, "^KS11",      "韩国KOSPI",  "KR", 37.57, 126.98, "KS11.KR",   false),
        new Spec(Source.YAHOO, "^FTSE",      "富时100",    "GB", 52.70, -1.80,  "FTSE.GB",   false),
        new Spec(Source.YAHOO, "^GDAXI",     "德国DAX",    "DE", 52.52, 13.40,  "GDAXI.DE",  false),
        new Spec(Source.YAHOO, "^FCHI",      "法国CAC40",  "FR", 47.50, 4.00,   "FCHI.FR",   false),
        new Spec(Source.YAHOO, "^TWII",      "台湾加权",   "TW", 25.03, 121.57, "TWII.TW",   false),
        new Spec(Source.YAHOO, "^STI",       "新加坡STI",  "SG", 1.35,  103.82, "STI.SG",    false),
        new Spec(Source.YAHOO, "^BSESN",     "印度SENSEX", "IN", 28.61, 77.23,  "BSESN.IN",  false),
        new Spec(Source.YAHOO, "^AXJO",      "澳洲ASX200", "AU", -35.28, 149.13, "AXJO.AU",  false),
        new Spec(Source.YAHOO, "^GSPTSE",    "加拿大TSX",  "CA", 49.28, -123.12, "GSPTSE.CA", false),
        new Spec(Source.YAHOO, "^BVSP",      "巴西Bovespa", "BR", -15.80, -47.86, "BVSP.BR",  false),
        // ── Global indicators ───────────────────────────────────────
        new Spec(Source.YAHOO, "DX-Y.NYB",   "美元指数",    null, 0, 0, "DXY.IDX", true),
        new Spec(Source.YAHOO, "GC=F",       "黄金期货/美元",   null, 0, 0, "XAU.CMD", true),
        new Spec(Source.YAHOO, "BTC-USD",    "比特币/美元", null, 0, 0, "BTC.CCY", true),
        new Spec(Source.YAHOO, "CL=F",       "WTI 原油期货",    null, 0, 0, "CL.CMD",  true)
    );

}
