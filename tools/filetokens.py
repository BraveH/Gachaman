"""Per-file stripped-token measurement, using tools/tokens.py's own stripper.

    python tools/filetokens.py <path> [<path> ...]

Reports the o200k_base token count of each file after comments and blank lines
are removed -- i.e. exactly the slice of the corpus that the Plugin Hub bot
charges for. Used to measure a compression pass file by file.
"""
import io, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tiktoken

# tokens.py runs main() at import time, so lift the stripper out by exec'ing
# only the source above the call. Cheap and keeps one definition of the rules.
src = io.open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tokens.py'),
              encoding='utf-8').read()
ns = {}
exec(src.split('def corpus(')[0].replace('import tiktoken', 'pass'), ns)
strip = ns['strip']

enc = tiktoken.get_encoding('o200k_base')
total = 0
for p in sys.argv[1:]:
    t = len(enc.encode(strip(io.open(p, encoding='utf-8').read()), disallowed_special=()))
    total += t
    print("%8d  %s" % (t, p))
print("%8d  TOTAL" % total)
