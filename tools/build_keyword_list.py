"""Builds app/src/main/assets/text/keywords.txt for Stage 4 (text scanning).

Source: LDNOOBW — "List of Dirty, Naughty, Obscene, and Otherwise Bad Words"
(https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words),
pinned to a fixed commit, licensed CC BY 4.0 (see third_party/ldnoobw/).

Methodology (also in README "Stage 4"):
 1. Take LDNOOBW's `en` and `ar` lists at the pinned commit.
 2. Keep only sexual / explicit / suggestive terms — the detector looks for sexual
    conversations, not rudeness. Excluded, with the reason recorded below:
      - slurs and hate terms (not sexual content),
      - generic swearing / insults used in ordinary angry chat,
      - violence and crime-news terms (would fire on news articles),
      - everyday words with a common innocent meaning (false positives),
      - medical-only terms that fire on health conversations.
 3. Add Egyptian Arabic slang, Franco-Arabic (Arabizi, digits for letters) and
    common evasion spellings (p0rn, pr0n, s3x are also handled generically by the
    matcher's normalisation; entries here cover spellings it can't derive).
 4. Add exception tokens ("!token") for innocent words that the matcher's affix
    tolerance would otherwise catch (e.g. "نيكون" Nikon, "cocky").

Matching rules (normalisation, affixes, leetspeak, spaced letters) live in the
app's KeywordMatcher, not here; this file only defines content.

Usage:  python tools/build_keyword_list.py
"""
import os
import urllib.request

LDNOOBW_COMMIT = "5faf2ba42d7b1c0977169ec3611df25a3c08eb13"
BASE = f"https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/{LDNOOBW_COMMIT}"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "text", "keywords.txt")

# ---- English exclusions (reason -> entries) ----
EN_EXCLUDE = {
    "slur / hate term, not sexual": [
        "beaner", "beaners", "coon", "coons", "darkie", "honkey", "jigaboo", "jiggaboo",
        "jiggerboo", "kike", "negro", "neonazi", "nigga", "nigger", "nig nog", "paki",
        "pikey", "raghead", "slanteye", "spic", "swastika", "towelhead", "wetback",
        "white power", "mong", "spastic", "fag", "faggot", "poof", "tranny", "bulldyke",
        "carpet muncher", "carpetmuncher", "fudge packer", "fudgepacker",
    ],
    "generic swearing / insult": [
        "apeshit", "arsehole", "ass", "asshole", "assmunch", "bastard", "bastardo", "bitch",
        "bitches", "bollocks", "bullshit", "clusterfuck", "fuck", "fuckin", "fucking",
        "fucktards", "motherfucker", "god damn", "piece of shit", "shit", "shitblimp",
        "shitty", "tosser", "fuck buttons", "\U0001F595",
    ],
    "violence / crime news": [
        "how to kill", "how to murder", "rape", "raping", "rapist", "date rape", "daterape",
        "bastinado", "strappado",
    ],
    "everyday word, innocent meaning common": [
        "butt", "suck", "sucks", "escort", "domination", "hardcore", "hard core", "cornhole",
        "circlejerk", "dingleberry", "dingleberries", "fingering", "jelly donut", "octopussy",
        "pissing", "shrimping", "snowballing", "tainted love", "tea bagging", "tit", "twinkie",
        "tushy", "scat", "snatch", "skeet", "spunk", "santorum", "eunuch", "ball kicking",
        "lolita", "tied up", "taste my", "tight white", "girl on", "big black", "huge fat",
        "tongue in a", "make me come", "xx", "cialis", "viagra", "sexual", "sexually",
        "sexuality",
        # found by the ordinary-text corpus check (see README):
        "xxx",          # chapter numbers (XXX), placeholders (XXX-XXX-XXXX), prices
        "intercourse",  # "social intercourse" in ordinary prose
        "s&m",          # normalises to "sm", matched "SMD" (surface-mount device)
    ],
    "medical-only / health": ["anus", "rectum", "fecal"],
}

# ---- Arabic exclusions ----
AR_EXCLUDE = {
    "everyday word, innocent meaning common": [
        "فرج",     # common male name (Farag) and "relief" (ربنا يفرجها)
        "جماع",    # collides with جماعة ("يا جماعة", everyone)
        "حلمة",    # normalises to حلمه = "his dream"
        "لعق", "لحس", "مص", "تمص",  # lick / suck: food, ordinary speech
        "بيضان",   # eggs / dialect
        "مبادل",   # exchanger (مبادل حراري)
        "شاذ",     # "irregular" (grammar, statistics)
        "شهوة",    # common in religious texts
        "لبوة",    # lioness
        # found by the ordinary-text corpus check (see README):
        "سحاق",    # collides with إسحاق / اسحاق (Isaac, e.g. Isaac Newton); سحاقية kept
        "قضيب",    # "rod / bar / rail" in technical text
        "نيك",     # transliterates "Nick" (نيك فيوري); unambiguous verb forms added below
    ],
    "medical / religious-jurisprudence / news": [
        "ثدي", "شرج", "خنثي", "احتلام", "اغتصاب", "لوطي", "لواط",
    ],
    "generic insult": ["عرص", "خول"],
}

# ---- Additions ----
EN_EXTRA = [
    # sexting / suggestive requests
    "nudes", "send nudes", "send nude", "nude pics", "dick pic", "dick pics", "cock pic",
    "slutty", "sexting", "sexted", "sext", "sexts", "nudez", "pussies", "wanna fuck",
    "fuck me", "friends with benefits", "hook up tonight", "horny af", "nsfw pics",
    "strip for me", "show me your body", "take it off for me",
    # adult sites / platforms
    "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "onlyfans",
    "only fans", "fansly",
    # evasion spellings the matcher can't derive (anagrams, digit spellings)
    "pr0n", "p0rno", "s3xy",
]

AR_EXTRA = [
    # Egyptian Arabic slang (sexual); written in normalised form (ة as ه, ا for أ/إ/آ)
    # (verb forms of the bare root نيك, which is excluded because it transliterates "Nick")
    "ينيك", "تنيك", "هنيك", "بنيك", "انيكك", "نيكني", "نيكها", "النيك", "نياكه",
    "متناك", "متناكه", "اتناك", "تناك", "منيوك", "منيوكه",
    # (زبر dropped: زبرة/زبره = zebra; زبي is covered by زب + ي; standalone زبي
    #  also took verb prefixes and matched حزبي "partisan")
    "زبري", "طيز", "بزاز", "كس", "شرموط", "شرموطه", "شراميط",
    "قحبه", "قحاب", "عاهر", "عاهره", "مومس", "دعاره", "سكس", "بورنو", "بورن هب", "بورنهب",
    "اباحي", "اباحيه", "افلام اباحيه", "عريانه", "صور عاريه", "بنات عاريات",
    # (نودز alone also means network "nodes"; only as a request)
    "ابعتلي نودز", "ابعتيلي نودز", "ابعت نودز", "ابعتي نودز", "صور نودز",
    "هايج", "هايجه", "اقلعي", "قلعي", "وريني جسمك", "وريني صدرك", "ليله حمرا",
    "صور عريانه", "فيديو سكس", "افلام سكس", "سحاقيه", "بظر", "مفلقسه",
    # Franco-Arabic / Arabizi (digits stand for Arabic letters: 2=ء/ق 3=ع 5=خ 7=ح)
    "neek", "nayek", "nayk", "neik", "metnak", "mtnak", "metnaka", "mtnaka",
    "manyouk", "manyok", "mnyok", "mnyouk", "sharmota", "sharmoota", "sharmot", "sharmouta",
    "a7ba", "2a7ba", "ka7ba", "kahba", "zeb", "zob", "zebr", "zobr", "teez",
    "bezaz", "bzaz", "kos", "koss", "kuss", "hayga", "e2la3y", "e2la3i",
    "2la3y", "2la3i", "3eryana", "3aryana", "seks", "seksy",
]

# Innocent tokens that the matcher's affix tolerance would otherwise catch.
EXCEPTIONS = [
    "cocky", "cocker", "cockers",
    "نيكون",   # Nikon
    "نيكو", "نيكي", "نيكول", "نيكولا", "انيكا",  # names Nico, Nicky, Nicole, Nicola, Anika
    "زبون", "زبونه", "زباين",  # customer(s)
    "كسكسي",   # couscous
    "اسحاق",   # Isaac
    "زبره", "زبرة",  # zebra
    "حزبي",    # partisan
]


def fetch(name: str):
    with urllib.request.urlopen(f"{BASE}/{name}") as r:
        return [l.strip() for l in r.read().decode("utf-8").splitlines() if l.strip()]


def main():
    en, ar = fetch("en"), fetch("ar")
    en_excluded = {e for items in EN_EXCLUDE.values() for e in items}
    unknown = en_excluded - set(en)
    assert not unknown, f"exclusions not in LDNOOBW en: {sorted(unknown)}"
    ar_excluded = {e for items in AR_EXCLUDE.values() for e in items}
    assert ar_excluded <= set(ar), f"exclusions not in LDNOOBW ar: {sorted(ar_excluded - set(ar))}"

    en_kept = [w for w in en if w not in en_excluded]
    ar_kept = [w for w in ar if w not in ar_excluded]
    en_extra = [w for w in EN_EXTRA if w not in en_kept]
    seen = set(ar_kept)
    ar_extra = []
    for w in AR_EXTRA:
        if w not in seen:
            seen.add(w)
            ar_extra.append(w)

    lines = [
        "# Guardian Stage 4 keyword list — GENERATED by tools/build_keyword_list.py; edit the",
        "# script (or this file) and rebuild the APK. No code change needed to change content.",
        f"# Base: LDNOOBW en + ar lists @ {LDNOOBW_COMMIT} (CC BY 4.0),",
        "#   filtered to sexual/explicit terms; see README 'Stage 4' for methodology.",
        "# Format: one word or phrase per line; '#' starts a comment; '!token' is an",
        "#   exception (a token that must never match). Case, diacritics, letter forms,",
        "#   leetspeak, repeated and spaced-out letters are handled by the matcher.",
        "",
        f"# --- LDNOOBW en (kept {len(en_kept)} of {len(en)}) ---",
        *en_kept,
        "",
        f"# --- LDNOOBW ar (kept {len(ar_kept)} of {len(ar)}) ---",
        *ar_kept,
        "",
        f"# --- Additions: English slang / sites / evasion spellings ({len(en_extra)}) ---",
        *en_extra,
        "",
        f"# --- Additions: Egyptian Arabic + Franco-Arabic ({len(ar_extra)}) ---",
        *ar_extra,
        "",
        f"# --- Exceptions ({len(EXCEPTIONS)}) ---",
        *[f"!{t}" for t in EXCEPTIONS],
        "",
    ]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"en kept {len(en_kept)}/{len(en)}, ar kept {len(ar_kept)}/{len(ar)}, "
          f"+{len(en_extra)} en, +{len(ar_extra)} ar/arabizi, {len(EXCEPTIONS)} exceptions -> {OUT}")


if __name__ == "__main__":
    main()
