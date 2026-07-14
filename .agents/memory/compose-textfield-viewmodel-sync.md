---
name: Compose TextField ↔ ViewModel sync
description: Pattern for glitch-free text input when a ViewModel needs to mirror and sometimes programmatically set the field text.
---

Driving a Compose `TextField` directly from a ViewModel StateFlow (`value = state.input`, `onValueChange = vm::onChanged`) reorders fast keystrokes and teleports the cursor ("backwards typing"), because each keystroke round-trips through the StateFlow and a stale snapshot can overwrite a newer local edit.

**The pattern that works:**
- Keep a local `TextFieldValue` in the composable (`remember(<identity keys>)`) as the single source of truth for text + cursor while typing.
- ViewModel state keeps a plain-string mirror for business logic (send enablement, payload).
- ViewModel bumps an explicit `inputSetSequence: Int` **only** on its programmatic writes (autofill, clear-on-send). A `LaunchedEffect(inputSetSequence)` copies VM → local (cursor to end). Never sync VM → local on ordinary recomposition.
- In `onValueChange`, forward text changes **comparing against the previous local value**, never against the VM mirror — the mirror is a recomposition snapshot that can lag one keystroke and swallow a rapid type-then-delete edit (caught by code review; the wrong text would then be sent).

**Why:** two independent writers (user typing, VM programmatic sets) need an explicit ownership protocol; equality guards against async snapshots silently drop edits.

**How to apply:** any future text input here that a ViewModel can also write to (search boxes, rename fields) should copy this structure from the follow-up input in ChatBubble.
