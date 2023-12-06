import numpy as numpy
import os

### aggregate all sequential results files into one file results-sequential.csv

folders = [f for f in os.listdir('.') if os.path.isdir(f) and f.startswith('results-speedups')]
print(folders)

for folder in folders:
    out = open(f'{folder}/results-sequential.csv', 'w')
    out.write('instance,time1,time2,time3,time4,time5,time6')

    files = [f for f in os.listdir(folder) if f.startswith('results-sequential') and f.endswith('.time')]
    files.sort()

    oldinstance = ''
    for file in files:
        # reove "results-sequential-" and "-i.time"
        instance = file[19:-7]
        if instance != oldinstance:
            out.write(f'\n{instance}')
            oldinstance = instance
        with open(folder + '/' + file, 'r') as f:
            time = f.readlines()[0].strip()
        out.write(f',{time}')
    out.close()