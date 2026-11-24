# Third-party data in chart-svc

## lasotuvi (Vietnamese school tables)

- Project: lasotuvi, <https://github.com/doanguyen/lasotuvi>
- Commit: `ace8379a3ea033674e8a9096f5b98a9e2041eace`
- Licence: MIT, Copyright (c) 2016 doanguyen
- Used for: the Vietnamese-school star brightness table (`dacTinhSao` in `lasotuvi/DiaBan.py`)
  and the natal Tứ Hóa table (the Tứ Hóa block in `lasotuvi/App.py`), transcribed as data into
  `src/domain/vn_school.rs`. No lasotuvi code is copied or linked; the cross-check fixture
  (`tests/fixtures/lasotuvi.json`) holds only its output.

```
MIT License

Copyright (c) 2016 doanguyen

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
