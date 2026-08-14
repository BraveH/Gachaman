"""Measure the plugin against the Plugin Hub's 200,000-token review limit.

    python tools/tokens.py            # measure the working tree
    python tools/tokens.py <git-ref>  # measure a commit

WHAT THE BOT COUNTS (established in commit 1ac9e3b): src/main/java only, with
comments and blank lines stripped. Tests, README, docs and every resource under
src/main/resources are free.

HOW THE NUMBER IS CALIBRATED
This script tokenises with tiktoken and then applies a correction, because the
bot's tokeniser is not public. Two figures are recorded in the git history and
both are reproducible here:

  commit    o200k_base (stripped)   bot said     ratio
  ca280de              243,700      249,632      1.0243
  73f21a5              202,333     ~206,000      1.0181   ("206k", so rounded)

So the bot reads about 2% above o200k_base on the stripped corpus, and the
range below reflects the spread of those two anchors. As a cross-check,
cl100k_base on the same corpus at 1ac9e3b gives 199,668 against the 199,830
recorded in that commit message — 0.08% out, which is what confirms the
STRIPPING is right even though the tokeniser is not identical.

Treat the HIGH number as the real one when deciding whether to submit. Being
wrong the other way costs a rejected submission.
"""
import io, os, subprocess, sys, tempfile, shutil

try:
    import tiktoken
except ImportError:
    sys.exit("pip install tiktoken")

LIMIT = 200_000
LOW, MID, HIGH = 1.016, 1.021, 1.025


def strip(src):
    """Drop comments and blank lines, leaving string/char literals intact."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n:
            if src[i + 1] == '/':
                while i < n and src[i] != '\n':
                    i += 1
                continue
            if src[i + 1] == '*':
                i += 2
                while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                    i += 1
                i += 2
                continue
        if c in '"\'':
            if src[i:i + 3] == '"""':
                out.append(src[i:i + 3])
                i += 3
                while i < n and src[i:i + 3] != '"""':
                    out.append(src[i])
                    i += 1
                out.append(src[i:i + 3])
                i += 3
                continue
            q = c
            out.append(c)
            i += 1
            while i < n and src[i] != q:
                if src[i] == '\\':
                    out.append(src[i])
                    i += 1
                    if i < n:
                        out.append(src[i])
                        i += 1
                    continue
                out.append(src[i])
                i += 1
            if i < n:
                out.append(src[i])
                i += 1
            continue
        out.append(c)
        i += 1
    text = ''.join(out)
    return '\n'.join(l for l in (x.rstrip() for x in text.splitlines()) if l.strip())


def corpus(root):
    parts, files = [], 0
    for dp, _, names in os.walk(root):
        for name in sorted(names):
            if name.endswith('.java'):
                parts.append(strip(io.open(os.path.join(dp, name), encoding='utf-8').read()))
                files += 1
    return "\n".join(parts), files


def main():
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ref = sys.argv[1] if len(sys.argv) > 1 else None
    tmp = None
    try:
        if ref:
            tmp = tempfile.mkdtemp()
            tar = subprocess.check_output(['git', 'archive', ref, 'src/main/java'], cwd=repo)
            p = os.path.join(tmp, 'a.tar')
            open(p, 'wb').write(tar)
            subprocess.check_call(['tar', '-xf', 'a.tar'], cwd=tmp)
            root = os.path.join(tmp, 'src', 'main', 'java')
        else:
            root = os.path.join(repo, 'src', 'main', 'java')

        text, files = corpus(root)
        raw = len(tiktoken.get_encoding('o200k_base').encode(text, disallowed_special=()))
        lo, mid, hi = int(raw * LOW), int(raw * MID), int(raw * HIGH)

        print("%s: %d files, %d stripped chars" % (ref or 'worktree', files, len(text)))
        print("  o200k_base       %8d tokens" % raw)
        print("  calibrated       %8d .. %d  (best guess %d)" % (lo, hi, mid))
        print("  limit            %8d" % LIMIT)
        if hi <= LIMIT:
            print("  VERDICT: under, with %d to spare even at the pessimistic end" % (LIMIT - hi))
        elif lo > LIMIT:
            print("  VERDICT: OVER by at least %d. Must reduce." % (lo - LIMIT))
        else:
            print("  VERDICT: AT THE LINE (%d over at worst, %d under at best)."
                  % (hi - LIMIT, LIMIT - lo))
            print("           Assume over; only the Hub bot can settle it.")
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)


main()
