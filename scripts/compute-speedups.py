import os
import pandas as pd
import matplotlib.pyplot as plt

### compute speedups based on results-sequential.csv and results-parallel.csv
time_columns = ['time1', 'time2', 'time3', 'time4', 'time5', 'time6']
folders = [f for f in os.listdir('.') if os.path.isdir(f) and f.startswith('results-speedups')]
print(folders)

for folder in folders:
    print(folder)
    df_sequential = pd.read_csv(f'{folder}/results-sequential.csv')
    df_sequential['time'] = df_sequential[time_columns].mean(axis=1)
    print(df_sequential)
    # df_sequential[time_columns].T.boxplot()
    # plt.show()

    df_parallel = pd.read_csv(f'{folder}/results-parallel.csv')
    df_parallel['time'] = df_parallel[time_columns].mean(axis=1)
    print(df_parallel)
    # df_parallel[time_columns].T.boxplot()
    # plt.show()

    df_speedups = pd.merge(df_sequential[['instance', 'time']], df_parallel[['instance', 'time']], on='instance', suffixes=('_sequential', '_parallel'))
    df_speedups['speedup'] = df_speedups['time_sequential'] / df_speedups['time_parallel']
    print(df_speedups)

    # save with 2 decimal places
    df_speedups.round(2).to_csv(f'{folder}/results-speedups.csv', index=False)

