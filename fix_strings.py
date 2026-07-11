import os
import re

def fix_apostrophes(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    def replacer(match):
        text = match.group(1)
        # Replace ' with \' if not already preceded by \
        # We use a negative lookbehind for \
        fixed_text = re.sub(r"(?<!\\)'", r"\'", text)
        return f'>{fixed_text}<'

    # Match content between > and <
    # [^<]+ ensures we don't match empty tags or just whitespace between tags if not needed
    new_content = re.sub(r'>([^<]+)<', replacer, content)

    if content != new_content:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f'Fixed {file_path}')

res_dir = 'app/src/main/res'
for root, dirs, files in os.walk(res_dir):
    if 'values-' in root and 'strings.xml' in files:
        fix_apostrophes(os.path.join(root, 'strings.xml'))
