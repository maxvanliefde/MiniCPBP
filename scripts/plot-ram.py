import matplotlib.pyplot as plt
import numpy as np

# Read data from ram-dump
with open('ram-dump', 'r') as f:
    lines = f.readlines()
    lines = [line.strip() for line in lines]

# x = every 0.5 sec
x = np.arange(0, len(lines) / 2, 0.5)
y = np.array(lines, dtype=np.int32)
plt.plot(x, y)
plt.show()
