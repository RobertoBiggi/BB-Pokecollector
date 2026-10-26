#!/usr/bin/env python3
"""
Genera cardmarket-extra-sets.tsv: i set giapponesi e inglesi che CardTrader/Cardmarket hanno e TCGdex no, con numero stampato
e id prodotto Cardmarket di ogni carta. Scrive anche cardmarket-ball-variants.tsv: le carte giapponesi con reverse holo Poké Ball e Master Ball, che su Cardmarket
sono prodotti a parte (set "... - Poké Ball Reverse Holo"). Tra gli inglesi molti set sembrano mancanti ma TCGdex li ha con un altro nome
(es. "Platinum Arceus" = pl4): l'app scarta le carte che TCGdex ha già (stesso prodotto Cardmarket) quando fa il sync.

Uso: CARDTRADER_TOKEN=... tools/gen_extra_sets.py [--report]   (--report: solo elenco dei set, senza scrivere il file)

Il confronto con TCGdex ripete CardTraderMatcher.expansionFor (stesso abbinamento che l'app usa per i prezzi):
un set TCGdex abbina un'espansione CardTrader, quelle non abbinate da nessuno sono i set mancanti.
Dopo una modifica agli alias di CardTraderMatcher.JP_CODE_ALIASES va riportata qui.
"""
import concurrent.futures as cf
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "core/src/main/resources/com/robertobiggi/cardcollection/core/cardmarket")
TOKEN = os.environ.get("CARDTRADER_TOKEN", "")
MIN_CONTAINED = 6
MIN_CARDS = 4

# Sigla stampata dei set giapponesi, quando non coincide con quella di CardTrader.
PRINTED = {
    "unpromo": ("unpromo", None), "dp4": ("DP4m", "DP4m"), "pt1": ("Pt1", "Pt1"), "pt2": ("Pt2", "Pt2"),
    "pt3": ("Pt3", "Pt3"), "aoa": ("Pt4", "Pt4"),
}

JP_ALIASES = {
    "pmcg1": "1ep", "pmcg2": "jun", "pmcg3": "myf", "pmcg4": "rog", "pmcg5": "gls", "pmcg6": "gcd",
    "neo1": "gstnw", "neo2": "ctr", "neo3": "awl", "neo4": "del", "vs1": "pvs", "web1": "pwe",
    "e1": "ec1", "e2": "ec2", "e3": "ec3", "e4": "ec4", "e5": "ec5",
    "pcg1": "fol", "pcg2": "cbs", "pcg3": "rgsb", "pcg4": "gsso", "pcg5": "mif",
    "pcg6": "hrt", "pcg7": "hof", "pcg8": "mic", "pcg9": "odffe",
    "s5i": "s5l", "sm1p": "sm1plus", "sm2p": "sm2plus", "sm3p": "sm3", "sm4p": "sm4", "sm5p": "sm5plus",
    "sv-p": "promosv", "m-p": "mp-promo",
    "adv1": "exp", "adv2": "mod", "adv3": "roh", "adv4": "mvsa", "adv5": "uns", "pcg10": "wcp",
    "l1a": "l1", "l1b": "l1ss", "xy1a": "xy1", "xy1b": "xy1y", "xy5a": "xy5", "xy5b": "xy5t",
    "xy8a": "xy8", "xy8b": "xy8r", "xy11a": "xy11f", "xy11b": "xy11", "cp6": "ep20", "s3a": "ls3",
}


# Set che CardTrader dichiara inglesi ma Cardmarket tiene tra i giapponesi (stesso id espansione di "CoroCoro Promo").
LANGUAGE = {"unpromo": "ja", "s-p": "ja"}
# Set cinesi (semplificato): "CS6b: Azure Shadow ...", "CSM2b: ...", "CSV9.5: ...".
CHINESE = re.compile(r"^CS[A-Za-z0-9.]*\s*:")
FOREIGN = re.compile(r"\b(french|german|italian|spanish|korean|chinese|thailand|indonesia)\b", re.I)


def get(url, auth=False):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + TOKEN} if auth else {})
    for attempt in range(8):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 7:
                raise
            time.sleep(2 + attempt * 2)


def norm(name):
    return "".join(c for c in name.lower().replace("pokémon", "").replace("pokemon", "") if c.isalnum())


def expansion_for(exps, name, set_id, code, prefer_code):
    wanted = norm(name)

    def by_name():
        return next((e for e in exps if norm(e["name"]) == wanted), None)

    def by_code():
        for c in [x.lower() for x in (set_id, code) if x]:
            for cand in ([JP_ALIASES[c]] if c in JP_ALIASES else []) + [c]:
                e = next((e for e in exps if (e.get("code") or "").lower() == cand), None)
                if e:
                    return e
        return None

    if prefer_code:
        exact = by_code() or (by_name() if wanted else None)
    else:
        exact = (by_name() if wanted else None) or by_code()
    if exact:
        return exact
    if len(wanted) >= MIN_CONTAINED:
        hit = [e for e in exps if len(norm(e["name"])) >= MIN_CONTAINED
               and (norm(e["name"]).endswith(wanted) or wanted.endswith(norm(e["name"])))]
        if len(hit) == 1:
            return hit[0]
    return None


def language_of(blueprints):
    for bp in blueprints:
        for p in bp.get("editable_properties") or []:
            if p.get("name") == "pokemon_language":
                v = p.get("possible_values") or []
                if "jp" in v:
                    return "ja"
                if "en" in v:
                    return "en"
                return None
    return None


BALL = re.compile(r"(Poké Ball|Master Ball) Reverse Holo$", re.I)
# Il codice CardTrader di questi set è "p-<set>" (Poké Ball) o "m-<set>" (Master Ball); questi non seguono la regola.
BALL_TARGET = {"151pkmr": "sv2a"}


def listing_language(bps):
    """Lingua delle carte in vendita su CardTrader per qualche carta del set: "jp", un'altra (es. "zh-CN") o None se non ce ne sono."""
    langs = set()
    sample = [bp for bp in bps if bp.get("card_market_ids") and (bp.get("fixed_properties") or {}).get("collector_number")][:4]
    for bp in sample:
        for products in get(f"https://api.cardtrader.com/api/v2/marketplace/products?blueprint_id={bp['id']}", True).values():
            langs.update(p["properties_hash"].get("pokemon_language") for p in products)
    langs.discard(None)
    if "jp" in langs:
        return "jp"
    return next(iter(sorted(langs)), None)


def main():
    report = "--report" in sys.argv
    if not TOKEN:
        sys.exit("serve CARDTRADER_TOKEN")
    cm = {}
    for line in open(os.path.join(RES, "cardmarket-expansions.tsv"), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 4 and p[0].isdigit() and p[3].strip().isdigit():
            cm[int(p[0])] = int(p[3])
    exps = [e for e in get("https://api.cardtrader.com/api/v2/expansions", True) if e["game_id"] == 5 and e["id"] in cm]
    matched = set()
    ja_ids = {}
    for lang in ("en", "ja"):
        for s in get(f"https://api.tcgdex.net/v2/{lang}/sets"):
            if lang == "ja":
                ja_ids[s["id"].lower()] = s["id"]
            # Un set TCGdex con pochissime carte (es. "Miscellaneous Promos", una sola) non copre l'espansione CardTrader che gli somiglia.
            if (s.get("cardCount") or {}).get("total", 0) < MIN_CARDS and s["id"].lower() not in ("sv-p", "m-p"):
                continue
            e = expansion_for(exps, s["name"], s["id"], None, lang == "ja")
            if e:
                matched.add(e["id"])
    missing = [e for e in exps if e["id"] not in matched]
    print(f"{len(exps)} espansioni, {len(missing)} senza set TCGdex", file=sys.stderr)

    def blueprints(e):
        return e, get(f"https://api.cardtrader.com/api/v2/blueprints/export?expansion_id={e['id']}", True)

    sets, cards, seen, balls = [], [], set(), []
    shared = {}
    for e in missing:
        shared[cm[e["id"]]] = shared.get(cm[e["id"]], 0) + 1
    with cf.ThreadPoolExecutor(2) as pool:
        for e, bps in pool.map(blueprints, missing):
            # Il nome dice che è un set in un'altra lingua (es. "Traditional Chinese Products"), anche se qualche blueprint elenca l'inglese.
            lang = None if FOREIGN.search(e["name"]) else LANGUAGE.get((e.get("code") or "").lower()) or language_of(bps)
            # I set giapponesi più vecchi non dichiarano la lingua: se non è dichiarata si considerano giapponesi, tranne quelli che lo dicono nel nome.
            if lang is None and not FOREIGN.search(e["name"]):
                # Cinese (es. "Collect 151", "Gem Pack"), coreano ecc. si riconoscono dalla lingua delle carte in vendita.
                sold = listing_language(bps)
                lang = "ja" if sold in (None, "jp") else None
            ball = BALL.search(e["name"])
            if ball and lang == "ja":
                code = (e.get("code") or "").lower()
                target = ja_ids.get(BALL_TARGET.get(code) or re.sub(r"^[pm]-", "", code))
                if target:
                    kind = "POKE_BALL" if ball.group(1).lower().startswith("pok") else "MASTER_BALL"
                    for bp in bps:
                        num = ((bp.get("fixed_properties") or {}).get("collector_number") or "").split("/")[0].strip()
                        if bp.get("card_market_ids") and num:
                            balls.append((target, num, kind, bp["card_market_ids"][0], bp["id"]))
                continue
            if lang not in ("ja", "en") or "reverse holo" in e["name"].lower() or CHINESE.match(e["name"]):
                continue
            linked = [(bp, bp["card_market_ids"][0]) for bp in bps if bp.get("card_market_ids")]
            code = (e.get("code") or "").lower()
            set_id, printed = PRINTED.get(code, (e.get("code") or str(e["id"]), None))
            linked = [(bp, pid) for bp, pid in linked if pid not in seen]
            whole = shared[cm[e["id"]]] == 1
            if not linked and not whole:
                continue
            seen.update(pid for _, pid in linked)
            # 1 se il set ha un'espansione Cardmarket tutta sua: allora anche i prodotti senza collegamento CardTrader sono suoi.
            sets.append((lang, set_id, printed or "", e["name"], cm[e["id"]], 1 if whole else 0))
            for bp, pid in linked:
                num = ((bp.get("fixed_properties") or {}).get("collector_number") or "").strip()
                cards.append((lang, set_id, num, pid))
    sets.sort()
    if report:
        for s in sets:
            print("\t".join(map(str, s)))
        return
    with open(os.path.join(RES, "cardmarket-extra-sets.tsv"), "w", encoding="utf-8") as f:
        for lang, set_id, printed, name, cmid, whole in sets:
            f.write(f"S\t{lang}\t{set_id}\t{printed}\t{name}\t{cmid}\t{whole}\n")
        for lang, set_id, num, pid in sorted(cards):
            f.write(f"C\t{lang}\t{set_id}\t{num}\t{pid}\n")
    with open(os.path.join(RES, "cardmarket-ball-variants.tsv"), "w", encoding="utf-8") as f:
        for row in sorted(set(balls)):
            f.write("\t".join(map(str, row)) + "\n")
    print(f"{len(sets)} set, {len(cards)} carte, {len(balls)} varianti Poké Ball / Master Ball", file=sys.stderr)


main()
