#!/usr/bin/env python2.7

import sys
import subprocess 
import os

cwd = os.getcwd()

for line in sys.stdin:
    f = cwd + '/' + line
    subprocess.call(['java', '-jar', '/Users/rbonifacio/Documents/saner2018/scripts/google-java-format.jar', '-i', f.strip()])
