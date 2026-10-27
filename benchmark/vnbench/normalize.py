# SPDX-License-Identifier: GPL-3.0-or-later
"""Text normalisation applied to the reference and the transcript before they are compared (docs/BENCHMARK.md).

Unicode NFKC, lower case, typographic apostrophes straightened, punctuation, symbols and hyphens turned into spaces
(an apostrophe inside a word stays), accents kept, filler words dropped, and number words turned into digits so that
"three hundred and forty", "driehonderdveertig" and "340" compare equal. Supported: whole numbers up to 9999 and spoken
digit sequences ("four seven one nine" is 4719). Not supported, so they count as errors: ordinals ("3rd"), clock times
("9:15" is read as "9 15", which only matches a reference spoken as two numbers), decimals and thousands separators.
"""
import re
import unicodedata

LANGUAGES = ("en", "nl", "fr")

FILLERS = {
    "en": {"um", "uh", "er", "erm", "hmm", "mm"},
    "nl": {"euh", "eh", "uhm", "hm", "hmm"},
    "fr": {"euh", "heu", "hum", "hm"},
}

_UNITS = {
    "en": ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
           "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"],
    "nl": ["nul", "een", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht", "negen", "tien", "elf", "twaalf",
           "dertien", "veertien", "vijftien", "zestien", "zeventien", "achttien", "negentien"],
    "fr": ["zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze",
           "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"],
}
_TENS = {
    "en": ["twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"],
    "nl": ["twintig", "dertig", "veertig", "vijftig", "zestig", "zeventig", "tachtig", "negentig"],
    "fr": ["vingt", "trente", "quarante", "cinquante", "soixante"],
}
_CONNECTORS = {"en": {"and"}, "nl": {"en"}, "fr": {"et"}}


def _strip_accents(word):
    return "".join(c for c in unicodedata.normalize("NFD", word) if not unicodedata.combining(c))


def _build_words(lang):
    words = {}
    for value, word in enumerate(_UNITS[lang]):
        if "-" not in word:
            words[word] = value
    for index, word in enumerate(_TENS[lang]):
        words[word] = (index + 2) * 10
    if lang == "en":
        words.update({"oh": 0, "hundred": 100, "thousand": 1000})
    elif lang == "nl":
        words.update({"één": 1, "honderd": 100, "duizend": 1000})
    else:
        words.update({"zéro": 0, "une": 1, "vingts": 20, "cent": 100, "cents": 100, "mille": 1000})
    return {_strip_accents(k): v for k, v in words.items()}


_WORDS = {lang: _build_words(lang) for lang in LANGUAGES}
_NL_UNITS = "een|twee|drie|vier|vijf|zes|zeven|acht|negen"
_NL_TENS_RE = "twintig|dertig|veertig|vijftig|zestig|zeventig|tachtig|negentig"


def _nl_compound(word):
    """Dutch compound numbers written as one word: driehonderdveertig, tweeentwintig, honderdvier. None if not one."""
    w = _strip_accents(word)
    total, rest = 0, w
    match = re.match(r"^(?:(%s)?duizend)(.*)$" % _NL_UNITS, rest)
    if match:
        total += (_WORDS["nl"][match.group(1)] if match.group(1) else 1) * 1000
        rest = match.group(2)
    match = re.match(r"^(?:(%s)?honderd)(.*)$" % _NL_UNITS, rest)
    if match:
        total += (_WORDS["nl"][match.group(1)] if match.group(1) else 1) * 100
        rest = match.group(2)
    if total == 0 and rest == w:
        # no thousands or hundreds: only "unit + en + tens" can still be a compound word
        match = re.match(r"^(%s)en(%s)$" % (_NL_UNITS, _NL_TENS_RE), rest)
        return _WORDS["nl"][match.group(1)] + _WORDS["nl"][match.group(2)] if match else None
    if rest == "":
        return total
    match = re.match(r"^(%s)en(%s)$" % (_NL_UNITS, _NL_TENS_RE), rest)
    if match:
        return total + _WORDS["nl"][match.group(1)] + _WORDS["nl"][match.group(2)]
    value = _WORDS["nl"].get(rest)
    return total + value if value is not None else None


def _word_value(word, lang):
    plain = _strip_accents(word)
    value = _WORDS[lang].get(plain)
    if value is None and lang == "nl":
        value = _nl_compound(word)
    return value


def _can_add(acc, x, lang):
    if acc == 0:
        return False
    if acc >= 1000 and acc % 1000 == 0:
        return x < 1000
    if acc >= 100 and acc % 100 == 0:
        return x < 100
    if acc >= 20 and acc % 10 == 0:
        if x < 10:
            return True
        return lang == "fr" and acc in (60, 80) and 10 <= x < 20  # soixante-dix, quatre-vingt-onze
    return lang == "fr" and acc == 10 and 7 <= x <= 9           # dix-sept, dix-huit, dix-neuf


def _combine(items, lang):
    """Turns a run of number words (ints, with "C" for a connector word between them) into digit tokens."""
    numbers = [x for x in items if x != "C"]
    if len(numbers) >= 2 and all(0 <= x <= 9 for x in numbers):
        return ["".join(str(x) for x in numbers)]          # spoken digit sequence: "four seven one nine" -> 4719
    if lang == "nl":                                        # twee en twintig = 22
        merged, k = [], 0
        while k < len(items):
            if (k + 2 < len(items) and isinstance(items[k], int) and 1 <= items[k] <= 9 and items[k + 1] == "C"
                    and isinstance(items[k + 2], int) and items[k + 2] in range(20, 100, 10)):
                merged.append(items[k] + items[k + 2])
                k += 3
            else:
                merged.append(items[k])
                k += 1
        items = merged
    out, big, acc = [], 0, None                             # big: the thousands, acc: the part below 1000

    def flush():
        if big or acc is not None:
            out.append(str(big + (acc or 0)))

    for x in items:
        if x == "C":
            continue
        if x >= 1000 and x % 1000 == 0:
            if big == 0 and acc is None:
                big = x
            elif big == 0 and x == 1000 and 1 <= acc <= 9:
                big, acc = acc * 1000, None                  # three thousand
            else:
                flush()
                big, acc = x, None
        elif acc is None:
            acc = x
        elif x == 100 and 1 <= acc <= 9:
            acc *= x                                        # two hundred
        elif lang == "fr" and acc == 4 and x == 20:
            acc = 80                                        # quatre-vingt
        elif _can_add(acc, x, lang):
            acc += x
        else:
            flush()
            big, acc = 0, x
    flush()
    return out


def _numbers_to_digits(tokens, lang):
    if lang == "en":                                        # "double oh" = 00, "triple" likewise
        expanded = []
        for i, token in enumerate(tokens):
            if token in ("double", "triple") and i + 1 < len(tokens) and _word_value(tokens[i + 1], "en") is not None:
                expanded.extend([tokens[i + 1]] * (1 if token == "double" else 2))
            else:
                expanded.append(token)
        tokens = expanded
    out, i, n = [], 0, len(tokens)
    while i < n:
        value = _word_value(tokens[i], lang)
        if value is None:
            out.append(tokens[i])
            i += 1
            continue
        run, j = [value], i + 1
        while j < n:
            if tokens[j] in _CONNECTORS[lang] and j + 1 < n and _word_value(tokens[j + 1], lang) is not None:
                run.append("C")
                j += 1
                continue
            v = _word_value(tokens[j], lang)
            if v is None:
                break
            run.append(v)
            j += 1
        if lang == "en" and run == [0] and tokens[i] == "oh":
            out.append(tokens[i])                           # a lone "oh" is an interjection
        else:
            out.extend(_combine(run, lang))
        i = j
    return out


_BREAK = "\x00"   # punctuation ends a run of number words ("three hundred forty, four" is two numbers)


def _join_single_digits(tokens):
    """Consecutive single digits are one digit string, whether they were spoken or written: "4 7 1 9" is 4719."""
    out, pending = [], []
    for token in tokens + [_BREAK]:
        if len(token) == 1 and token.isdecimal():
            pending.append(token)
            continue
        if pending:
            out.append("".join(pending))
            pending = []
        if token != _BREAK:
            out.append(token)
    return out


def normalize(text, lang):
    """The comparable form of `text` as a list of tokens. `lang` is "en", "nl" or "fr"."""
    if lang not in LANGUAGES:
        raise ValueError("unsupported language: %r" % lang)
    text = unicodedata.normalize("NFKC", text).lower()
    text = text.replace("\u2019", "'").replace("\u2018", "'").replace("\u02bc", "'").replace("`", "'")
    text = text.replace("\u0153", "oe").replace("\u00e6", "ae")
    kept = []
    for c in text:
        category = unicodedata.category(c)
        if c == "'" or category[0] in "LNM":
            kept.append(c)
        elif category == "Pd" or c.isspace():
            kept.append(" ")                                # hyphens and spaces separate words but do not end a number
        else:
            kept.append(" " + _BREAK + " ")                 # other punctuation and symbols
    tokens = [t if t == _BREAK else t.strip("'") for t in "".join(kept).split()]
    tokens = [t for t in tokens if t and t not in FILLERS[lang]]
    return _join_single_digits(_numbers_to_digits(tokens, lang))
