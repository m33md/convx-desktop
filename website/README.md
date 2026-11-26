# Convx website

The Convx website: a home page (`index.html`), Docs, Q&A and Privacy (`docs.html`, `faq.html`,
`privacy.html`), a not-found page (`404.html`) and their pictures in `assets/`. Plain HTML, CSS and
JavaScript with no build step; open `index.html` in a browser to see it. The pages need an
internet connection for their fonts, the animation library (GSAP, from cdnjs) and the download
links.

To put it online anywhere else, upload this folder's contents as they are to any static host,
such as Netlify, Cloudflare Pages or a GitHub Pages repository. `index.html` is the home page and
`404.html` the not-found page.

Every push to `main` that changes this folder publishes it to
https://m33md.github.io/convx-desktop/ (`.github/workflows/website.yml`). GitHub Pages has to be set
to "GitHub Actions" once, in the repository's Settings > Pages. If the site moves to a domain of its
own, change the address in the `canonical`, `og:url` and `og:image` tags of each page, in
`robots.txt` and in `sitemap.xml`.

Downloads: each download button works without JavaScript, opening the release page. With it, the
page asks GitHub's API for the newest release and links straight to the file for the visitor's
system (the Windows setup, the Apple silicon or Intel disk image, or the newest Android release),
with its version and size. The answer is kept in the browser for ten minutes, because GitHub
allows 60 unsigned requests an hour from one address.

The lyrics in the lyrics demo were written for the page. Screenshots come from the desktop app's
CI screenshots and the Android app's posters. The link preview (`assets/og.jpg`) is a picture of
the page's own top section.
