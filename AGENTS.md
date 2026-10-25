# Instructions for coding agents

These rules apply to every agent working in this repository (Claude Code, Codex, Copilot and
others) and override any default habit. This repository is public.

## Never publish personal data

Never write the rider's personal data into anything that ends up in this repository or on GitHub:
code, comments, tests, test data, README and other docs, commit messages, branch names, tags,
pull request titles and descriptions, review and issue comments, and release notes.

Personal data includes, about the rider or their rides:

- body data: weight, height, age, heart rate, FTP or any other fitness figure
- ride details: distances, durations, dates, times, speeds, power or heart-rate values, and
  anything else measured on a specific ride, even rounded or as an example ("on an N km ride",
  "X km/h at a junction", "heart rate rose by Y bpm")
- location: coordinates, routes, place or street names, regions, the country, and terrain
  descriptions that identify where they ride
- device identifiers: sensor IDs, serial numbers and anything else that singles out one device
  (naming equipment in general, such as a sensor model or a hub type, is fine)
- identity and accounts: names, emails, usernames other than the repository owner's GitHub
  handle, API keys, tokens and passwords

Rides, exports and settings stay outside the repository: never commit `.fit`, `.gpx` or ride CSV
files, or a rider's config JSON, and don't add them to tests. Analysis scripts that read them live
outside the repository too.

## How to write instead

- Describe findings generically: "on replayed rides", "on short steep climbs", "matched an
  energy check within a few watts". No numbers or places taken from a particular ride.
- Tests use neutral, made-up values: an 80 kg rider, round speeds, synthetic roads at arbitrary
  coordinates (e.g. 50.0, 10.0).
- Defaults and their reasoning cite physics or general sources, not the rider's data.

## Before every commit, push, PR or comment

Read the full diff and the message or text you are about to publish, and remove anything from the
list above. If unsure whether something is personal, leave it out. History is public once pushed:
fix it before pushing, never by a later commit.
