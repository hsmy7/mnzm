#!/usr/bin/env python3
"""
Convert HalfScreenDialog to full-screen Dialog.
Strategy: find HalfScreenDialog + Column(fillMaxSize) pattern,
replace opening, find matching } for Column and HalfScreenDialog,
add Box/Surface/Dialog closes.
"""
import re, os, sys

DIR = r"C:\Mnzm\XianxiaSectNative\android\app\src\main\java\com\xianxia\sect\ui\game"

# (file, skip_count): skip first N HalfScreenDialog matches before converting
# skip_count=0 means convert the first match; skip_count=1 means skip first, convert second
TARGETS = [
    # Direct HalfScreenDialog files - convert first (and only) match
    ("AlchemyScreen.kt", 0),
    ("ForgeScreen.kt", 0),
    ("WenDaoPeakScreen.kt", 0),
    ("QingyunPeakScreen.kt", 0),
    ("TianshuHallScreen.kt", 0),
    ("LawEnforcementHallScreen.kt", 0),
    ("RecruitScreen.kt", 0),
    # Merchant has 3 matches - convert first (main dialog), skip sub-dialogs
    ("MerchantScreen.kt", 0),
    # CommonDialog files - convert the private CommonDialog (later in file)
    ("SpiritMineScreen.kt", 0),
    ("HerbGardenScreen.kt", 0),
    ("LibraryScreen.kt", 0),
    ("MissionHallScreen.kt", 0),
    ("ReflectionCliffScreen.kt", 0),
    # WorldMapDialogs.kt - DiplomacyDialog is the second match
    ("dialogs/WorldMapDialogs.kt", 1),
    # AllianceDialog.kt
    ("components/AllianceDialog.kt", 0),
]

FULLSCREEN_OPEN = '''    Dialog(
        onDismissRequest = DISMISS,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = GameColors.PageBackground
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.bg_horizontal),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.fillMaxSize()) {'''

HSD_PATTERN = re.compile(r'^(\s*)HalfScreenDialog\(onDismissRequest\s*=\s*(.+?)\)\s*\{$')
COL_PATTERN = re.compile(r'^(\s*)Column\(modifier\s*=\s*Modifier\.fillMaxSize\(\)\)\s*\{$')

def find_matching_brace(lines, start_idx):
    """Find index of matching } for { on line start_idx."""
    depth = 0
    started = False
    for i in range(start_idx, len(lines)):
        line = lines[i]
        for ch in line:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                depth -= 1
                if started and depth == 0:
                    return i
    return -1

def process_file(filepath, skip_count):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    lines = content.split('\n')

    # Replace import
    for idx, line in enumerate(lines):
        if 'import com.xianxia.sect.ui.components.HalfScreenDialog' in line:
            lines[idx] = 'import androidx.compose.ui.window.DialogProperties'
            break

    # Find all HalfScreenDialog + Column patterns
    matches = []
    i = 0
    while i < len(lines):
        m1 = HSD_PATTERN.match(lines[i])
        if m1 and i + 1 < len(lines):
            m2 = COL_PATTERN.match(lines[i + 1])
            if m2:
                dismiss_param = m1.group(2)
                hsd_close = find_matching_brace(lines, i)
                if hsd_close >= 0:
                    # Also find Column close
                    col_close = find_matching_brace(lines, i + 1)
                    matches.append((i, i + 1, col_close, hsd_close, dismiss_param))
        i += 1

    if not matches:
        print("  No HalfScreenDialog+Column pattern found")
        return False

    # Only convert the target match
    if skip_count >= len(matches):
        print(f"  skip_count={skip_count} but only {len(matches)} matches, skipping")
        return False

    hsd_open, col_open, col_close, hsd_close, dismiss_param = matches[skip_count]

    if col_close < 0 or hsd_close < 0:
        print("  ERROR: could not find matching close braces")
        return False

    print(f"  Converting at line {hsd_open+1} (HSD) -> {hsd_close+1} (close), Column close at {col_close+1}")

    # Build the replacement
    open_text = FULLSCREEN_OPEN.replace('DISMISS', dismiss_param)

    # Replace:
    #   lines[hsd_open] (HalfScreenDialog) → new opening
    #   lines[col_open] (Column) → removed (already in new opening)
    #   lines[hsd_close] (HalfScreenDialog close) → removed (replaced by Box/Surface/Dialog close)
    #   After Column close, add Box, Surface, Dialog closes

    # The Column close at col_close is inside the HalfScreenDialog lambda
    # After Column closes, we need: Box close, Surface close, Dialog close
    # Then the old HalfScreenDialog close at hsd_close is removed

    # Structure:
    #   ... content before hsd_open ...
    #   new opening (Dialog, Surface, Box, Image, Column)
    #   ... content between col_open+1 and col_close (Column body) ...
    #   (Column close stays at col_close position)
    #   Box close, Surface close, Dialog close (after col_close, replacing hsd_close)
    #   ... content after hsd_close+1 ...

    # Count how many lines to remove: hsd_open, col_open, hsd_close = 3 lines removed
    # How many lines to add: len(open_text_lines) + 3 (Box, Surface, Dialog closes)

    open_lines = open_text.split('\n')

    # Build new lines
    new_lines = (
        lines[:hsd_open] +                          # before HalfScreenDialog
        open_lines +                                # new opening (Dialog/Surface/Box/Image/Column)
        lines[col_open + 1:col_close + 1] +         # Column body + Column close
        ['    }', '}', '    }'] +                   # Box, Surface, Dialog closes
        lines[hsd_close + 1:]                       # after old HalfScreenDialog close
    )

    result = '\n'.join(new_lines)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(result)
    return True

def main():
    for filename, skip in TARGETS:
        filepath = os.path.join(DIR, filename)
        if not os.path.exists(filepath):
            print(f"SKIP (not found): {filepath}")
            continue
        print(f"Processing {filename}...")
        try:
            process_file(filepath, skip)
        except Exception as e:
            print(f"  ERROR: {e}")
            import traceback
            traceback.print_exc()

if __name__ == '__main__':
    main()
