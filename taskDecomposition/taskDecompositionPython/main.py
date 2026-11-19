"""Build Exercise 1.6 - multi-pass code review vs single-pass review.

    python main.py                 # review ../sample-pr both ways and compare
    python main.py <directory>     # any directory with 10+ .js/.ts files

Results are saved to results.json, so you can read every finding.
"""

import json
import sys
import time
from pathlib import Path

from compare import comparison_table
from llm import ClaudeLLM
from review import load_files, multi_pass, single_pass


def main() -> None:
    directory = sys.argv[1] if len(sys.argv) > 1 else Path(__file__).parent.parent / "sample-pr"
    files = load_files(directory)
    llm = ClaudeLLM()
    print(f"Reviewing {len(files)} files from {directory}\n")

    t = time.time()
    single = single_pass(files, llm)
    print(f"single pass: 1 call, {time.time() - t:.0f}s")

    t = time.time()
    multi = multi_pass(files, llm)
    print(f"multi-pass:  {len(files)} local calls + 1 integration call, {time.time() - t:.0f}s\n")

    print(comparison_table(list(files), single, multi))
    print("\nCross-file issues from the integration pass:")
    for issue in multi["cross_file_issues"]:
        print(f"  [{issue['severity']}] {issue['files']}: {issue['description']}")

    out = Path(__file__).parent / "results.json"
    out.write_text(json.dumps({"single": single, "multi": multi}, indent=2))
    print(f"\nAll findings saved to {out.name}")


if __name__ == "__main__":
    main()
