# CI workflow template

`ci.yml` is the GitHub Actions workflow for this repo (shader golden check, JUnit tests, debug APK).
It lives here because the access token used for the first push could not create files under `.github/workflows/`.

To activate it, do either of these:

* GitHub web UI: **Add file -> Create new file**, name it `.github/workflows/ci.yml`, paste the contents of `ci.yml`.
* Or give the token the **Workflows: Read and write** permission, then `git mv tools/ci/ci.yml .github/workflows/ci.yml`.
