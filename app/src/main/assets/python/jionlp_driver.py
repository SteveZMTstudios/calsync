# Placeholder driver for a future embedded-Python runtime on Android.
#
# Intended contract:
# - input: text (str), base_millis (int)
# - output: JSON dict with keys: start, end, title, location (epoch millis)
#
# This file is shipped so the Kotlin side can later call into it once
# CPython 3.14 Android testbed (or another runtime) is integrated.

import json


def parse(text: str, base_millis: int | None = None) -> str:
    # TODO: integrate JioNLP's time_parser once the runtime is available.
    # Return empty result for now.
    _ = (text, base_millis)
    return json.dumps({})
