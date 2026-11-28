"""The unicode escapes of a Java source, decoded as the compiler does before it reads anything else, line
terminators included.

This module is not a gate and imports nothing of the gates: check-private-io.py and the rules of
check-module-boundaries.py share it; their self-tests cover it.
"""

from __future__ import annotations

import re


_UNICODE_ESCAPE = re.compile(r"(?<!\\)((?:\\\\)*)\\u+([0-9a-fA-F]{4})")


def decode_unicode_escapes(source: str) -> str:
    """Decode unicode escapes before lexing, as the Java compiler does (an escaped backslash is not one). The
    compiler ends a line at a carriage return too, escaped or not: every terminator reads as a line feed."""
    decoded = _UNICODE_ESCAPE.sub(lambda match: match.group(1) + chr(int(match.group(2), 16)), source)
    return decoded.replace("\r\n", "\n").replace("\r", "\n")
