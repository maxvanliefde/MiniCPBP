import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

files = ['results-nworkers/results-nworkers-ColouredQueens-07.csv', 'results-nworkers/results-nworkers-DeBruijnSequence-03-03.csv']

for file in files:
    df = pd.read_csv(file, sep=',', header=0, index_col=0)
    df = df.T

    # plot
    plt.figure()
    fs=16
    bp = df.boxplot(fontsize=fs)
    plt.xlabel('Nombre de travailleurs', fontsize=fs)
    plt.ylabel('Durée d\'exécution [s]', fontsize=fs)
    plt.ylim(0)
    bp.figure.set_size_inches(7,5)

    # save
    plt.savefig(file.replace('.csv', '.pdf'))