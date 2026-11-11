# Convx website

The Convx website: one static page (`index.html`) with its pictures in `assets/`. No build step;
open `index.html` in a browser to see it.

Every push to `main` that changes this folder publishes it to
https://m33md.github.io/convx-desktop/ (`.github/workflows/website.yml`).

The download buttons link to the release pages and, when GitHub answers, switch to the newest
version's number (and, for Windows, straight to `Convx-Setup-<version>.exe`).

The lyrics in the lyrics demo were written for the page. Screenshots come from the Windows app's
CI screenshots and the Android app's posters.
