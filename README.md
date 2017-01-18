# GDQ Tracker Scraper

Pulls data from GDQ's donors tracker and donation comments.

https://gamesdonequick.com/tracker/donors/

I made this to filter donation comments matching specific substrings and never
made it into a standalone utility. There is no entry point. There is no
documentation.

The attached *.edn files contain all raw donors and donation comments data. They
have the format ```{id (list-of-entries)}``` where id is either the page number
for donors or the donor-id for donation comments.

This format was ideal to scrape, but isn't convenient to work with. The source
file contains code to load these data files and transform them into structures
easier to work with. There are also functions to filter and output this data.

Have fun!
