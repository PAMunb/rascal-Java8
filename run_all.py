import csv
import sys, os, getopt
import pathlib  

cwd = os.getcwd()
file_name = cwd+'/docker/input.csv'
rootdir = cwd+'/../dataset/'

   
for file in os.listdir(rootdir):
    d = os.path.join(rootdir, file)
    if os.path.isdir(d):
        project = d.replace(rootdir,"")

        with open(file_name, 'w', encoding='UTF8',newline='') as f:

            writer = csv.writer(f)

            os.chdir(d)

            os.system(f"git reset --hard")

            os.chdir(cwd)

            data = []
        
            data.append([project,'123123','AC','100','/home/dataset/'+project])
            data.append([project,'123123','FP','100','/home/dataset/'+project])
            data.append([project,'123123','EP','100','/home/dataset/'+project])
            data.append([project,'123123','FUNC','100','/home/dataset/'+project])
            # data.append([project,'123123','MR','100','/home/dataset/'+project])
            
            writer.writerows(data)

            f.close()

            os.chdir(cwd+"/docker/")

            os.system(f"docker build -t rjtl -f Dockerfile.rj .")

            os.system(f"docker run --name rjtl -w /home/rascal-Java8/ -it -v /home/walterlucas/Documents/dataset:/home/dataset -v /home/walterlucas/Documents/rascal-Java8/output:/home/rascal-Java8/output rjtl python3 driver.py -i home/input.csv > ../output/{project}.txt")

            os.system(f"docker rm rjtl && docker rmi rjtl")

            os.chdir(cwd)
