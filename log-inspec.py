import os
import csv


data = []
header = ['Project', 'aic', 'filter', 'exist', 'ForLoopToFunctional', 'Errors']
cwd = os.getcwd()

def Average(lst):
    return sum(lst)/len(lst)

path_of_the_directory = 'output/'
start = ('log-')
for log in os.listdir(path_of_the_directory):
    if log.startswith('.git'):
        continue
    TotalTransformations = []
    FileErrors = []
    projectName = ''
    kindTransformation = ''
    if log.startswith(start):
        file = open("output/"+log, 'r')
        for line in file:
            if line.startswith('[Project Analyzer] processing project: ') and projectName == '':
                laux = line.replace('[Project Analyzer] processing project: ','')
                projectName = laux.strip()

            if line.startswith('- Total of transformations:'):

                TotalTransformations.append(int(line.strip().replace("- Total of transformations: ", "")))
            if line.startswith('- Errors:'):

                FileErrors.append(int(line.strip().replace("- Errors: ", "")))
                kindTransformation = ''
        data.append((projectName,TotalTransformations[0],TotalTransformations[1],TotalTransformations[2],TotalTransformations[3],round(Average(FileErrors),2)))
        file.close()


with open('results.csv', 'w', encoding='UTF8', newline='') as f:
    writer = csv.writer(f, doublequote=False)

    # write the header
    writer.writerow(header)

    # write multiple rows
    writer.writerows(data)
