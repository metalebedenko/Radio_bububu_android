#!/usr/bin/env python3
"""Verify/regenerate the Telegram emoji ranges from a local official Unicode 17.0 UCD.

Supply emoji-data.txt (ucd/emoji/), UnicodeData.txt and DerivedCoreProperties.txt (ucd/)
from https://www.unicode.org/Public/17.0.0/ucd/. No network or installed ICU is required.
"""
import argparse
import hashlib
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/'app/src/main/java/ru/bububu/radio/sources/TelegramEmoji.java'
PINS={
    'emoji-data.txt':'2cb2bb9455cda83e8481541ecf5b6dfda66a3bb89efa3fa7c5297eccf607b72b',
    'UnicodeData.txt':'2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c',
    'DerivedCoreProperties.txt':'24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08'
}
# Emoji can also denote flags, ratings and semantic operators; preserve these bases.
PROTECTED=re.compile(r'FLAG|STAR|CURRENCY|DOLLAR|TRADE MARK|COPYRIGHT|REGISTERED|PLUS SIGN|MINUS SIGN|DIVISION SIGN|MULTIPLICATION|INFINITY|EQUALS SIGN')

def properties(lines,wanted):
    result={name:set() for name in wanted}
    for line in lines:
        record=line.partition('#')[0].strip()
        if not record:continue
        interval,name,*_=map(str.strip,record.split(';'))
        if name in result:
            endpoints=[int(k,16) for k in interval.split('..')]
            result[name].update(range(endpoints[0],endpoints[-1]+1))
    return result

def boundaries(values):
    edges=[]
    for cp in sorted(values):
        if edges and edges[-1]==cp:edges[-1]+=1
        else:edges.extend([cp,cp+1])
    return edges

def tables(root):
    files={}
    for filename,pin in PINS.items():
        data=(root/filename).read_bytes()
        if hashlib.sha256(data).hexdigest()!=pin:raise ValueError('Unexpected Unicode data: '+filename)
        files[filename]=data.decode().splitlines()
    e=properties(files['emoji-data.txt'],('Emoji','Extended_Pictographic','Emoji_Presentation','Emoji_Component','Emoji_Modifier_Base'))
    math=properties(files['DerivedCoreProperties.txt'],('Math',))['Math']
    scope=(e['Emoji'] & e['Extended_Pictographic']) - e['Emoji_Component'] - math
    symbols=set()
    for line in files['UnicodeData.txt']:
        code,name,category,*_=line.split(';');cp=int(code,16)
        if cp in scope and category=='So' and not PROTECTED.search(name):symbols.add(cp)
    return {'DEFAULT_EMOJI':symbols & e['Emoji_Presentation'],
            'EMOJI_WITH_PRESENTATION':symbols-e['Emoji_Presentation'],
            'MODIFIER_BASE':symbols & e['Emoji_Modifier_Base']}

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('ucd',type=Path)
    ap.add_argument('--write',action='store_true',help='Replace generated arrays (default: verify).')
    args=ap.parse_args()
    source=OUTPUT.read_text()
    prefix=source.split('    private static final int[] DEFAULT_EMOJI')[0]
    for name,values in tables(args.ucd).items():
        edges=boundaries(values)
        prefix+='    private static final int[] '+name+' = {\n'
        for i in range(0,len(edges),12):
            prefix+='        '+', '.join(f'0x{v:X}' for v in edges[i:i+12])+',\n'
        prefix+='    };\n'
        print(name,len(values),len(edges)//2)
    generated=prefix+'}\n'
    if args.write:OUTPUT.write_text(generated)
    elif source!=generated:raise SystemExit('Generated table differs')
if __name__=='__main__':main()
