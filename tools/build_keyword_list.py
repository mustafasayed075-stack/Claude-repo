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

# ---- English exclusions (reason -> entries): not sexual content, so never matched ----
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
}

# ---- Context rules: term -> innocent-context companions ----
# These terms have a common innocent or technical meaning. Rather than excluding them,
# each is matched unless one of its companions appears near it in the same sentence
# (and no other explicit term is in that sentence). Multi-word companions use a space.
# Chosen on the *dev* half of the ordinary-text corpora; measured on the held-out
# *test* half (README "Stage 4").
EN_CONTEXT = {
    # "sex" meaning gender ("the opposite sex", "same-sex") and in crime reporting
    "sex": "opposite same gender other bias assault offender offenders offence offense discrimination education trafficking",
    "butt": "kick kicked kicking cigarette cigarettes rifle gun joke jokes heads head",
    "suck": "straw thumb vacuum air blood juice up",
    "sucks": "this that it life traffic weather team game job movie class exam work so really",
    "escort": "police vessel ship ships convoy security guard guards military troops soldiers car ford mission motorcade",
    "domination": "world market military global economic team game league political sports empire",
    "hardcore": "development developer fans fan music punk rock band gamer gamers gaming workout training supporter supporters mode player players",
    "hard core": "fans fan music punk rock band gamer gamers gaming workout training supporter supporters mode player players",
    "cornhole": "game board bags tournament toss backyard yard",
    "circlejerk": "reddit thread sub subreddit forum echo",
    "dingleberry": "",
    "dingleberries": "",
    "fingering": "guitar piano violin chord chords notes scale bass flute instrument technique taste execution play playing",
    "jelly donut": "bakery coffee breakfast dunkin shop sugar glazed",
    "octopussy": "bond film movie 007 moore",
    "pissing": "rain raining down off contest about around",
    "shrimping": "boat boats shrimp fishing season net nets gulf trawler",
    "snowballing": "effect debt costs problem problems crisis rolling quickly fast snow",
    "tainted love": "song \"soft cell\" cover band album",
    "tea bagging": "game gaming halo players online match kill",
    "tit": "tat bird birds blue great coal",
    "twinkie": "snack hostess cake cream box lunch defense",
    "tushy": "baby diaper rash bidet",
    "scat": "singing jazz sing singer singers music animal droppings wildlife",
    "snatch": "thief thieves bag purse phone victory win defeat jaws weightlifting grab stole gold title medal application memory",
    "skeet": "shooting shoot shooter clay trap range gun olympic",
    "spunk": "courage spirit determination character plucky",
    "santorum": "rick senator campaign republican gop pennsylvania election candidate",
    "eunuch": "palace court emperor dynasty ottoman china historical ancient servant",
    "ball kicking": "football soccer match practice drill kids players goal",
    "lolita": "nabokov novel book fashion film kubrick style dress gothic",
    "tied up": "work busy meeting meetings traffic phone call boat dog \"loose ends\" office moment",
    "taste my": "food cake soup recipe dish sauce pie cookies dinner coffee tea drink cooking",
    "tight white": "shirt jeans pants dress top sneakers socks",
    "girl on": "phone team bike bus train street screen stage instagram tv show left right cover fire",
    "big black": "dog car cat bag box hole cloud eyes hat suv truck bird bear coat jacket boots door horse",
    "huge fat": "cat pay bonus raise salary paycheck lie mistake check",
    "tongue in a": "cheek",
    "make me come": "over back home down up with early late again to here there",
    "xx": "love kiss kisses chromosome chromosomes century chapter part name phone number format اسم اسمي رقم هاتف",
    "cialis": "doctor pharmacy prescription medicine drug pill dose heart pressure pfizer generic",
    "viagra": "doctor pharmacy prescription medicine drug pill dose heart pressure pfizer generic",
    "sexual": "harassment assault abuse violence health education orientation identity reproductive transmitted crimes crime misconduct allegations rights minorities humiliation",
    "sexually": "harassed assaulted abused transmitted active explicit",
    "sexuality": "education identity orientation gender rights human",
    "xxx": "chapter part vol volume phone number format price dollars dollar bowl olympiad pounds code name اسم اسمي رقم هاتف سعر دولار",
    "intercourse": "social friendly familiar daily commercial trade business polite pleasant conversation society family human cultural intellectual frequent constant delightful gaieties renewed acquaintance friends",
    "anus": "cancer surgery doctor hospital medical colon bowel anatomy disease patient fissure hemorrhoids colorectal biopsy",
    "rectum": "cancer surgery doctor hospital medical colon bowel anatomy disease patient fissure hemorrhoids colorectal biopsy",
    "fecal": "matter sample samples test bacteria transplant contamination coliform occult water",
}

# ---- Arabic exclusions: not sexual content, so never matched ----
AR_EXCLUDE = {
    "violence / crime news (like English 'rape')": ["اغتصاب"],
    "slur (anti-gay)": ["لوطي", "لواط"],
    "generic insult": ["عرص", "خول"],
}

AR_CONTEXT = {
    # everyday / technical meanings (previously excluded outright)
    "فرج": "ربنا الله يارب رب قريب همك كربك كرب هم ضيق دعاء اللهم الكرب الهم عم استاذ دكتور حج الحاج ابو ام محمد احمد سيد",
    "جماع": "حكم كفاره صيام رمضان نهار الصوم فقه فتوي شرعا الحج الاحرام",
    "حلمة": "يحقق تحقيق تحقق طموح مستقبل حياته عمره انه يكون يبقي يسافر يصبح الحلم رضاعه رضيع طفل مولود",
    "لعق": "ملعقه عسل \"ايس كريم\" اصابع طعام قطه كلب جرح",
    "بيضان": "فراخ بيض طبق كرتونه اومليت مسلوق مقلي فطار سعر اسعار دجاج",
    "مبادل": "حراري حراره تبريد تكييف مياه طاقه عمله تجاري تجاره اسهم سندات صرف صندوق استثمار حصه اسري محتجزين اجانب سلع شبكات",
    "شاذ": "فعل قاعده نحو صرف قياس جمع احصاء بيانات قيمه قيم استثناء سلوك تفسير فريد",
    "شهوة": "الله رمضان صيام نفس النفس دين عباده تقوي الدنيا المال الطعام الاكل السلطه الحكم",
    "لبوة": "اسد اسود غابه حديقه حيوان حيوانات سفاري صيد شبل اشبال",
    # medical / religious-jurisprudence meanings (previously excluded outright)
    "ثدي": "غرسات سرطان الكشف فحص اشعه ماموجرام طبيب دكتور مستشفي رضاعه رضيع طبي اورام اكتشاف مبكر توعيه زراعه تجميل",
    "شرج": "طبيب دكتور جراحه عمليه بواسير ناسور شرخ مستشفي علاج مرض قولون فتحه منظار",
    "خنثي": "طبي حاله جراحه فقه حكم مولود طفل هرمونات",
    "احتلام": "بلوغ غسل الغسل حكم صيام رمضان فقه طهاره مراهق مراهقه",
    # over-restricted in the first false-positive cleanup (now context-ruled instead)
    "قضيب": "حديد معدن معدني خرساني صلب تسليح نحاس المونيوم سكه قطار حديديه تنظيف محور مكبس توصيل فوهه اسطواني برغي ميكانيكي مغناطيس كهرباء كهربائي تحكم وقود نووي سلك بندقيه صيد ستاره",
    "نيك": "مارفل ممثل مغني لاعب تنس مدرب شخصيه النجم فيوري جوناس كارتر كيرجيوس كيريوس نولتي كيج رائد فضاء ناسا الامريكي الاميركي الامريكيان الاميركيان",
    "نايك": "كوتشي كوتش حذاء جزمه شوز سنيكرز اديداس بوما ماركه ماركات براند تيشيرت رياضي تريننج شنطه لوجو محل متجر جوردن شركه شركات كوكاكولا فيتون ابل",
    "زبر": "حيوان حمار وحشي مخطط مخططه حديقه غابه اسد زرافه سافاري خطوط عبور مشاه زرار كباسين جيب جيوب",
    "بورن": "جيسون دراجون ديمون مات برشلونه حي كاتالونيا كوميديا موسيقي اغاني فكاهه ساخره",
    "نودز": "شبكه كلاستر سيرفر سيرفرات خوادم بلوك بلوكتشين بلوكشين عقد عقده جراف شجره كود برمجه خوارزميه كمبيوتر حواسيب داتا بيانات بايثون جافا وصل بيتوصلوا ببعض خلايا عصبيه استيراد اورج رسومي",
    "عاريه": "تماما الصحه الياف سلك اسلاك ايد ايدي يد بيد العين بالعين عين الحقيقه حقيقه جدران جدار حيطان ارض اقدام قدم صخور جبال اشجار فروع اغصان شجر",
    "عاريات": "الياف سلك اسلاك جدران اشجار فروع اغصان",
}

# Arabic verb roots: all standard derived forms are generated by the matcher
# (ArabicMorphology.derive); an optional context rule applies to every form.
AR_ROOTS = {
    "ن ي ك": "",       # نيك ناك نايك منيوك اتناك متناك تناك نياك (+ affixes); نيك/نايك keep their own rules above
    "ش ر م ط": "",     # شرمط شرموط اتشرمط متشرمط شراميط
    "ل ح س": "جزم جزمه اقدام رجلين حذاء بياده كلامه كلام وعده وعوده مخه دماغه عقله \"ايس كريم\" جيلاتي بسكوت شيكولاته ملعقه صحن طبق كلب قطه القطه الكلب اصابع صوابع عسل مربي",
    "م ص ص": "قصب عصير شفاطه دم دماء سيجاره شيشه ليمون مانجا مصاصه بونبوني حلويات اصابع صوابع ابهام صباع الشعب فلوس",
    # (ه ي ج was tried and dropped: its forms collide with Egyptian هيجي/هيجوا/هاجي
    #  "will come" = هـ + يجي, all over the dev corpus. هايج/هايجه stay as plain entries.)
}

# Arabic nouns matched in noun mode (`=word`): article/preposition prefixes and
# pronoun endings only — their letters are also productive verb/adjective stems
# (اتفرج "watch", اجماع "consensus", جماعي "collective", الثدييات "mammals").
# Corroboration-only (`?word`): the innocent sense can't be captured by companions
# (names, idioms, ordinary usage); these count only alongside an unambiguous
# explicit term in the same sentence. Chosen from the dev corpus (README "Stage 4").
WEAK = {"فرج", "مبادل", "حلمة", "suck", "sucks",
        # added after the held-out test split (README): no genuine use in dev or test
        "xx", "شاذ", "بيضان"}

AR_NOUN_MODE = {"فرج", "جماع", "ثدي", "شرج", "مبادل", "شهوة", "لبوة", "حلمة", "بيضان", "خنثي",
                "قضيب", "نودز", "عاريه", "عاريات", "بورن", "زبر"}

AR_CONTEXT["kos"] = "theta sin cos tan"
AR_CONTEXT["بورنو"] = "ولايه نيجيريا مايدوغوري يوب بوكو حرام"  # Borno state, Nigeria  # Arabizi كس; also a variable name in maths code

# LDNOOBW's own entries that are forms of a context-ruled root take the root's rule.
AR_CONTEXT.update({"لحس": AR_ROOTS["ل ح س"], "مص": AR_ROOTS["م ص ص"], "تمص": AR_ROOTS["م ص ص"]})

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
    # Egyptian Arabic slang (sexual); written in normalised form (ة as ه, ا for أ/إ/آ).
    # Verb forms of نيك / لحس / مص / شرمط / هيج come from AR_ROOTS.
    "انيكك", "نيكني", "نيكها", "نايك", "زبر", "زبري", "طيز", "بزاز", "كس", "قحبه", "قحاب",
    "عاهر", "عاهره", "مومس", "دعاره", "سكس", "بورن", "بورنو", "بورن هب", "بورنهب",
    "اباحي", "اباحيه", "افلام اباحيه", "عريانه", "عاريه", "عاريات", "نودز",
    "هايجه", "اقلعي", "قلعي", "وريني جسمك", "وريني صدرك", "ليله حمرا",
    "صور عريانه", "فيديو سكس", "افلام سكس", "سحاقيه", "بظر", "مفلقسه",
    # Franco-Arabic / Arabizi (digits stand for Arabic letters: 2=ء/ق 3=ع 5=خ 7=ح)
    "neek", "nayek", "nayk", "neik", "metnak", "mtnak", "metnaka", "mtnaka",
    "manyouk", "manyok", "mnyok", "mnyouk", "sharmota", "sharmoota", "sharmot", "sharmouta",
    "a7ba", "2a7ba", "ka7ba", "kahba", "zeb", "zob", "zebr", "zobr", "teez",
    "bezaz", "bzaz", "kos", "koss", "kuss", "hayga", "e2la3y", "e2la3i",
    "2la3y", "2la3i", "3eryana", "3aryana", "seks", "seksy",
]

# Innocent tokens that the matcher's affix/derivation tolerance would otherwise catch.
EXCEPTIONS = [
    "cocky", "cocker", "cockers",
    "نيكون",   # Nikon
    "نيكو", "نيكي", "نيكول", "نيكولا", "انيكا",  # names Nico, Nicky, Nicole, Nicola, Anika
    "زبون", "زبونه", "زباين",  # customer(s)
    "كسكسي",   # couscous
    "اسحاق",   # Isaac (سحاق is matched otherwise)
    "حزبي",    # partisan (ح + زبي)
    "هناك",    # "there" (ه + ناك, a derived form of ن ي ك)
    "جماعه", "جماعات", "جماعتي", "جماعتك", "جماعتنا", "جماعتهم",  # group(s) (جماع + ه/ات/تي…)
    "مصاصه",   # straw / lollipop
    # found on the dev corpus after adding root derivations / affixes:
    "الحسين", "حسين", "الحس", "الحسي", "الحسيه", "الحسيات",  # Hussein; "the sense", sensory (ا + لحس…)
    "تناكه",   # Egyptian "snobbery" (تناك + ه)
    "بناك",    # "built you" (ب + ناك)
    "ثدييات", "ثديات", "ثديي",  # mammals / a mammal
    "الحسني",  # أسماء الله الحسنى (ا + لحس + ني)
    "جماعي", "جماعيه", "جماعيا",  # collective (جماع + ي/يه)
    "فرجه",    # Egyptian "spectacle / watching" (فرجة)
    "الحاسه",  # "the sense" (ا + لحاس + ه)
    "اناك",    # Anak (Krakatau) / "he came to you"
    # found on the held-out test split:
    "زبره",    # zebra (زبرة); زبري/زبرك/زبرها… still match
    "بزي",     # "in (civilian) clothes" (ب + زي): بزي مدني
    "الكسي",   # Alexei (ال + كس + ي)
    "جماعك",   # typo of جماعة
]


def fetch(name: str):
    with urllib.request.urlopen(f"{BASE}/{name}") as r:
        return [l.strip() for l in r.read().decode("utf-8").splitlines() if l.strip()]


def ctx(term: str, rules: dict) -> str:
    head = ("=" + term) if term in AR_NOUN_MODE else term
    if term in WEAK:
        return "?" + head
    companions = rules.get(term, None)
    if companions is None:
        return head
    return f"{head} ~ {companions}".rstrip(" ~") + ("" if companions else "  # context rule: none (always matches)")


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
    missing = [t for t in EN_CONTEXT if t not in en_kept + en_extra] + \
              [t for t in AR_CONTEXT if t not in ar_kept + ar_extra]
    assert not missing, f"context rules for terms not in the list: {missing}"

    lines = [
        "# Guardian Stage 4 keyword list — GENERATED by tools/build_keyword_list.py; edit the",
        "# script (or this file) and rebuild the APK. No code change needed to change content.",
        f"# Base: LDNOOBW en + ar lists @ {LDNOOBW_COMMIT} (CC BY 4.0),",
        "#   filtered to sexual/explicit terms; see README 'Stage 4' for methodology.",
        "# Format: one word or phrase per line; '#' starts a comment; '!token' is an",
        "#   exception (a token that must never match); 'term ~ w1 w2 \"w 3\"' is a context",
        "#   rule (suppressed when a companion word is near it in the same sentence);",
        "#   '@root ن ي ك' generates the root's derived forms. Case, diacritics, letter",
        "#   forms, leetspeak, repeated/spaced/masked letters and affixes are handled by",
        "#   the matcher.",
        "",
        f"# --- LDNOOBW en (kept {len(en_kept)} of {len(en)}; {sum(t in EN_CONTEXT for t in en_kept)} with context rules) ---",
        *[ctx(w, EN_CONTEXT) for w in en_kept],
        "",
        f"# --- LDNOOBW ar (kept {len(ar_kept)} of {len(ar)}; {sum(t in AR_CONTEXT for t in ar_kept)} with context rules) ---",
        *[ctx(w, AR_CONTEXT) for w in ar_kept],
        "",
        f"# --- Additions: English slang / sites / evasion spellings ({len(en_extra)}) ---",
        *[ctx(w, EN_CONTEXT) for w in en_extra],
        "",
        f"# --- Additions: Egyptian Arabic + Franco-Arabic ({len(ar_extra)}) ---",
        *[ctx(w, AR_CONTEXT) for w in ar_extra],
        "",
        f"# --- Arabic roots ({len(AR_ROOTS)}): derived forms generated by the matcher ---",
        *[(f"@root {r} ~ {c}" if c else f"@root {r}") for r, c in AR_ROOTS.items()],
        "",
        f"# --- Exceptions ({len(EXCEPTIONS)}) ---",
        *[f"!{t}" for t in EXCEPTIONS],
        "",
    ]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    n_ctx = sum(1 for l in lines if " ~ " in l and not l.startswith("@root"))
    print(f"en kept {len(en_kept)}/{len(en)}, ar kept {len(ar_kept)}/{len(ar)}, "
          f"+{len(en_extra)} en, +{len(ar_extra)} ar/arabizi, {len(AR_ROOTS)} roots, "
          f"{n_ctx} context rules, {len(EXCEPTIONS)} exceptions -> {OUT}")


if __name__ == "__main__":
    main()
