# Future requests

## Plain-text tab import/export

Add a simple tab-list interchange path:

- Export the current open tabs to a plain-text file, one URL per line.
- Import a plain-text file containing newline-delimited URLs and open those URLs as tabs.
- Keep the format deliberately simple: newline-delimited URLs should require no special conversion step.

Motivation: when a large set of tabs needs to be cleared or moved elsewhere, it should be possible to dump the tab URLs to ordinary text and later restore them directly.
