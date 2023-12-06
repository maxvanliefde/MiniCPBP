import matplotlib.pyplot as plt
import numpy as np
import os

# all from ram folder
filenames = os.listdir('ram')
print(filenames)

for filename in filenames:
    with open(f"ram/{filename}", 'r') as f:
        lines = f.readlines()
        lines = [line.strip() for line in lines]

    plt.figure(figsize=(6, 4))
    x = np.arange(0, len(lines) / 2, 0.5) # every 0.5 sec
    y = np.array(lines, dtype=np.int32)
    plt.plot(x, y)
    plt.xlabel('Temps [s]')
    plt.xlim(x[0], x[-1])
    plt.ylim(0, 4000)
    plt.ylabel('RAM utilisée [MiB]')
    plt.grid(True)
    plt.savefig(f'{filename}.pdf')
