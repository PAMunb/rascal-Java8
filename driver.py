#!/usr/bin/python3

import sys, os, getopt
import logging 
import git

logging.basicConfig(level = logging.INFO)

def main(argv):
    cwd = os.getcwd()

    input_dir = ''

    opts, args = getopt.getopt(argv, "i:", ["input_dir="])

    for opt, arg in opts:
        if opt in ('-i', '--input_dir'):
            input_dir = arg
        else:
            print("Path not found or no exists! Please, try again.")
            sys.exit(0)

    print(input_dir)

    logging.info(f"  RJTL: Loading and start rejuvenation process...")

    # repo = git.Repo(input_dir)

    # if not (branch in [r.name for r in repo.references]):
    #     repo.git.branch(branch)
        
    # repo.git.checkout(branch)

    # logging.info("Executing the migrations")

    os.system(f"java -Xmx4G -Xss1G -jar rascal-shell-stable.jar Driver -path {input_dir}")

    # os.chdir(input_dir)

    logging.info("  Formating the source code") 

    os.system(f"git diff -U0 HEAD^ | {cwd}/google-java-format-diff.py -p1 -i --google-java-format-jar {cwd}/google-format.jar")
    
    logging.info("   Done")

if __name__ == "__main__":
    main(sys.argv[1:])
