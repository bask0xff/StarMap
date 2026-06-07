import pandas as pd
df = pd.read_csv("hyg_v42.csv")
bright = df[df["mag"] < 6.5].copy()
bright.to_csv("stars_bright.csv", index=False)
print(len(bright), "звёзд")