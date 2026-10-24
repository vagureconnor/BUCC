# Vendored AutoTD backend

These files were copied from `AutoTD-Desktop` commit `92be6f8` and its bundled
`autotd-buaa` 0.1.15 source. The upstream project identifies CC as the desktop
application author and `autoTD maintainers` as the Python package authors.

The source checkout contained no project license file or package license
metadata. `src/auto_td/` and `default_images/` must not be redistributed in a
BUCC release until the project maintainers confirm distribution terms. This
notice records that limitation instead of assigning an invented license.

The copied runtime has no third-party Python dependencies. BUCC changes are
limited to a foreground cancellation endpoint used by the native Compose UI.
