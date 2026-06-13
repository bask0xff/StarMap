# StarMap

**StarMap** is a flexible, standalone tool designed for precise astronomical coordinate calculations and automated rendering of high-definition star maps (planispheres) based on the observer's exact geographic location (latitude, longitude), elevation, and precise timestamp.

The project is built as an isolated computing module capable of processing massive stellar catalogs and converting them into high-resolution graphic projections suitable for printing, desktop publishing, or web integration.

---

## 🌌 Key Features

*   **Astronomical Engine**: Full calculation of celestial body movements (stars, Solar System planets, and the Moon) taking into account Earth's axial precession, nutation, and atmospheric refraction.
*   **Sky Culture Layers**: Native support for various cultural interpretations of constellations (Western, Arabic, Egyptian, Chinese, etc.) by parsing external sky-culture configuration frameworks (such as Stellarium `.fab` file formats).
*   **Dynamic Magnitude Filtering**: Dynamically filters and renders objects based on their apparent magnitude (Magnitude Limit)—from bright navigational stars to fainter Deep Sky Objects (DSOs).
*   **Projection Rendering**: High-accuracy mathematical plotting of stereographic and orthographic projections of the celestial dome, centered precisely on the observer's zenith.
*   **Graphics Customization**: Granular control over visual mapping styles, including color palettes, font labeling, coordinate grids, and toggleable constellation boundaries or artwork lines.

---

## 🛠 Tech Stack & Data Sources

*   **Core Language**: Python 3.8+ / JavaScript (depending on the specific deployment target environment).
*   **Mathematical Libraries**: `Skyfield` (ephemeris and space mechanics), `PyEphem`, `NumPy`, and `SciPy` (matrix manipulations for geometric projections).
*   **Star Catalogs (Embedded & Dynamic)**:
    *   *Yale Bright Star Catalog* — for major navigational stars.
    *   *Hipparcos Catalog (HIP)* — the primary baseline database for high-precision stellar positions.
    *   *Messier Catalog* — for rendering nebulae, star clusters, and galaxies.
    *   *JPL DE421 ephemeris (NASA)* — for accurate sun, moon, and planetary calculations.
*   **Graphic Rendering**: Vector map outputs in **SVG** format and high-res raster outputs in **PNG/JPEG** utilizing (`Matplotlib` / native JS Canvas APIs).

---

## 📂 Project Structure

```text
├── data/                    # Local astronomical catalogs (.edb, .txt) and parsers
│   ├── catalog.txt          # Deep Sky Objects (DSO) dataset
│   ├── constellation_bounds # IAU official constellation boundary lines
│   └── skycultures/         # Star lines and constellation names across different cultures
├── pkg / src/               # Application core logic
│   ├── astro_engine.py      # Core logic for converting equatorial coordinates to horizontal
│   ├── projection.py        # Implementation of celestial dome stereographic projections
│   └── renderer.py          # Graphics engine for drawing lines, gradients, and object markers
├── config.json              # Global visual parameter presets
└── requirements.txt         # List of external runtime dependencies
```

## 🚀 Quick Start
Environmental Requirements
Ensure you have Python 3.8+ and the pip package manager installed on your operating system.

1. Installation
Clone the repository and install all required external dependencies within a virtual environment:
```bash
git clone [https://github.com/bask0xff/StarMap.git](https://github.com/bask0xff/StarMap.git)
cd StarMap
python -m venv .venv
source .venv/bin/activate  # On Windows use: .venv\Scripts\activate
pip install -r requirements.txt
```

## 2. Generating a Map via CLI
To create a sky map for a specific point on Earth, execute the main script with parameters defining your observing conditions:
```bash
python starmap.py \
  --lat 55.7558 \
  --lon 37.6173 \
  --elevation 150 \
  --date "2026-06-08T23:30:00Z" \
  --mag_min 4.5 \
  --skyculture western \
  --output ./london_night_sky.svg
```

## 📜 License
This software is distributed under the open-source MIT License. You are completely free to use, modify, and redistribute this codebase for both personal and commercial purposes. The full text of the license is available in the LICENSE file.




