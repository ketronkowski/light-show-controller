# Changelog

## [2.1.0](https://github.com/ketronkowski/light-show-controller/compare/v2.0.0...v2.1.0) (2026-11-28)


### Features

* Boundary finder on the phone ([#61](https://github.com/ketronkowski/light-show-controller/issues/61)) ([e9ffe40](https://github.com/ketronkowski/light-show-controller/commit/e9ffe40008cb21a07a85664adf1d3d4c03e489ab))
* camera light test lights each model through FPP and grades it with the show camera ([#41](https://github.com/ketronkowski/light-show-controller/issues/41)) ([4d0b0c7](https://github.com/ketronkowski/light-show-controller/commit/4d0b0c7f147f6559f30efc12cef92d98854cc46f))
* color the Show Control board lights by on, off and not answering ([#73](https://github.com/ketronkowski/light-show-controller/issues/73)) ([e9d845e](https://github.com/ketronkowski/light-show-controller/commit/e9d845e7c86dba5eb358d211c44d977677c45851))
* consistent page layout across the app, plus a phone screenshot script ([#62](https://github.com/ketronkowski/light-show-controller/issues/62)) ([3fe99e1](https://github.com/ketronkowski/light-show-controller/commit/3fe99e1498f0f19a32d0c076d0a06d78597f0569))
* install the app on a phone's home screen ([#63](https://github.com/ketronkowski/light-show-controller/issues/63)) ([79ff473](https://github.com/ketronkowski/light-show-controller/commit/79ff47395296e64ef94c6cd96df66a09e3db106b))
* jukebox interrupt test in the pre-show check ([#65](https://github.com/ketronkowski/light-show-controller/issues/65)) ([53f5dfc](https://github.com/ketronkowski/light-show-controller/commit/53f5dfc7aabe5b01f1341e98f6dbcc6462f78cd5))
* Latest light test page with each model's picture from the latest test and calibration ([#56](https://github.com/ketronkowski/light-show-controller/issues/56)) ([963339c](https://github.com/ketronkowski/light-show-controller/commit/963339cde1de3a23d899a242accd538a2d0f07d7))
* live stream viewers and upload on the Show Control page ([#70](https://github.com/ketronkowski/light-show-controller/issues/70)) ([23395e9](https://github.com/ketronkowski/light-show-controller/commit/23395e9344afaf35327026709fef174d19380e04))
* models that don't light in the camera light test are warnings, so the show still gets a GO ([#67](https://github.com/ketronkowski/light-show-controller/issues/67)) ([df9d637](https://github.com/ketronkowski/light-show-controller/commit/df9d637e70b9c9aeb92393fd7c08b12b6b413380))
* power graph hover readout and Cloudflare login look ([#48](https://github.com/ketronkowski/light-show-controller/issues/48)) ([9577db7](https://github.com/ketronkowski/light-show-controller/commit/9577db784335c777cacebd0a68b3960e2961a8e1))
* Pre-show check on the phone, in the app's dark look ([#60](https://github.com/ketronkowski/light-show-controller/issues/60)) ([1e1ee33](https://github.com/ketronkowski/light-show-controller/commit/1e1ee338ab233979e53a36a4e4058580b32b27bc))
* read-only Show Control page ([#46](https://github.com/ketronkowski/light-show-controller/issues/46)) ([8676598](https://github.com/ketronkowski/light-show-controller/commit/86765980e70a1a9aad71ea1b5a3b6faa9935cc87))
* recalibrate only the models the camera couldn't see, after a repair ([#54](https://github.com/ketronkowski/light-show-controller/issues/54)) ([e2c30f0](https://github.com/ketronkowski/light-show-controller/commit/e2c30f0e3a3235beb60728ca67484fe8d7c0bad2))
* Show Control page has an on/off button for the road speakers ([#57](https://github.com/ketronkowski/light-show-controller/issues/57)) ([01dab19](https://github.com/ketronkowski/light-show-controller/commit/01dab19c90b83bb1aca251c5b84caef92cf875e8))
* show the request queue and requests per household on Show Control ([#76](https://github.com/ketronkowski/light-show-controller/issues/76)) ([059aa4d](https://github.com/ketronkowski/light-show-controller/commit/059aa4d85171209d99083672afc8d8f3cee97158))
* show which invite links are watching on the Live stream card ([#72](https://github.com/ketronkowski/light-show-controller/issues/72)) ([d780385](https://github.com/ketronkowski/light-show-controller/commit/d780385cfb9116f84cdd74075d69ff032b1ed99e))
* stream lip-sync result on the Pre-show page and in the pre-show report ([#68](https://github.com/ketronkowski/light-show-controller/issues/68)) ([66a7757](https://github.com/ketronkowski/light-show-controller/commit/66a77571eb2f4b7ff5782ef74a650af664942c44))
* tab bar between pages (bottom bar on phone, tabs on laptop) ([#59](https://github.com/ketronkowski/light-show-controller/issues/59)) ([3ce33f5](https://github.com/ketronkowski/light-show-controller/commit/3ce33f5e38b7b438bff657c6175d51d513d4a544))
* the camera light test grades only whether each model lights, and treats hidden peace stakes as a note ([#55](https://github.com/ketronkowski/light-show-controller/issues/55)) ([51e68cd](https://github.com/ketronkowski/light-show-controller/commit/51e68cdacd40f830006f00d905e1eceb4bb6b70f))
* the light test switches off show boards on for the test and back off after ([#69](https://github.com/ketronkowski/light-show-controller/issues/69)) ([48556c6](https://github.com/ketronkowski/light-show-controller/commit/48556c6dc9035ac2ed7f56166b8dce6b37fcb674))


### Bug Fixes

* a light test or calibration started by hand isn't blocked by show hours ([#49](https://github.com/ketronkowski/light-show-controller/issues/49)) ([ab1e2a5](https://github.com/ketronkowski/light-show-controller/commit/ab1e2a5c2629c21ce559d82b9558ab3363a7af76))
* judge standby current against boards-on or boards-off baseline ([#75](https://github.com/ketronkowski/light-show-controller/issues/75)) ([e1dd89a](https://github.com/ketronkowski/light-show-controller/commit/e1dd89a1ba274c87698de6d5121cb538492606b7))
* show estimated current for WLED boards with per-output limits ([#71](https://github.com/ketronkowski/light-show-controller/issues/71)) ([d09f776](https://github.com/ketronkowski/light-show-controller/commit/d09f776cef9e79b8226912da828a5c870ce82104))
* the camera calibration doesn't fail on FPP's plain-text replies or stay busy after an error ([#50](https://github.com/ketronkowski/light-show-controller/issues/50)) ([5180edc](https://github.com/ketronkowski/light-show-controller/commit/5180edcb158ae0a618a543a142bd2d1a4a0c9ec1))
* the camera calibration no longer misses the first model it lights ([#53](https://github.com/ketronkowski/light-show-controller/issues/53)) ([047fbee](https://github.com/ketronkowski/light-show-controller/commit/047fbee242f7e76cf7f80fa03be39772a88d45af))
* the camera calibration uses far less memory and reports an out-of-memory failure ([#52](https://github.com/ketronkowski/light-show-controller/issues/52)) ([006323f](https://github.com/ketronkowski/light-show-controller/commit/006323f722c5b5068af45c32672d0261c78de0af))
* the camera snapshot accepts the UniFi console's certificate when reached by IP address ([#51](https://github.com/ketronkowski/light-show-controller/issues/51)) ([e7bc2b2](https://github.com/ketronkowski/light-show-controller/commit/e7bc2b2d15a62a18a32a4f6f0479a88d5dece45c))
* the jukebox test's cleanup accepts FPP's plain-text reply to Stop ([#66](https://github.com/ketronkowski/light-show-controller/issues/66)) ([6c2f3e5](https://github.com/ketronkowski/light-show-controller/commit/6c2f3e56d2bc26011d662799f19001fe4ce91e1c))

## [2.0.0](https://github.com/ketronkowski/light-show-controller/compare/v1.0.0...v2.0.0) (2026-11-23)


### ⚠ BREAKING CHANGES

* the container image is now ghcr.io/ketronkowski/light-show-controller, the release jar is light-show-controller.jar, and the app is served at light-show.lab.ri.tronkowski.net (the old host redirects).
* daily read-only pre-show check with push and email report ([#34](https://github.com/ketronkowski/light-show-controller/issues/34))

### Features

* daily read-only pre-show check with push and email report ([#34](https://github.com/ketronkowski/light-show-controller/issues/34)) ([dedd199](https://github.com/ketronkowski/light-show-controller/commit/dedd19965d4c659fa08c0bdb03ebd11460367380))
* give floodlights their own look in Set Look ([#30](https://github.com/ketronkowski/light-show-controller/issues/30)) ([6698fa4](https://github.com/ketronkowski/light-show-controller/commit/6698fa4168499fa3699aa3b1eaed46cee860b78e))
* merge back-to-back null models into one WLED segment ([#37](https://github.com/ketronkowski/light-show-controller/issues/37)) ([a9508ad](https://github.com/ketronkowski/light-show-controller/commit/a9508ad20490c75dada2fa5e09197bc7fb0d92e5))
* merge xLights model groups into single WLED segments ([#31](https://github.com/ketronkowski/light-show-controller/issues/31)) ([a5bf163](https://github.com/ketronkowski/light-show-controller/commit/a5bf163cd7fd42a33644beafe6aca18c2f2a353d))
* phone layout for the sync console and Set Look dialog ([#45](https://github.com/ketronkowski/light-show-controller/issues/45)) ([2e949e9](https://github.com/ketronkowski/light-show-controller/commit/2e949e93e16b61bbdafbf4a2e90ee021c15f69a4))
* pre-show check and HA alert for missing live-stream audio ([#40](https://github.com/ketronkowski/light-show-controller/issues/40)) ([a76eda7](https://github.com/ketronkowski/light-show-controller/commit/a76eda7c80fd4359b1b662b2c81ead0fbae3786c))
* pre-show check reports whether the iPhone camera feed is live ([#39](https://github.com/ketronkowski/light-show-controller/issues/39)) ([4c95c7e](https://github.com/ketronkowski/light-show-controller/commit/4c95c7e9245b01e5aa441a7687acb61ffe9ccc5a))
* rename the app to Light Show Controller ([#43](https://github.com/ketronkowski/light-show-controller/issues/43)) ([d405035](https://github.com/ketronkowski/light-show-controller/commit/d405035bb4bfd918f02c05ce4b678047d66abe00))
* save new segments into each board's boot preset so they survive a reboot ([#36](https://github.com/ketronkowski/light-show-controller/issues/36)) ([c1aeef6](https://github.com/ketronkowski/light-show-controller/commit/c1aeef64ceaf17af6f5b93cfed6b1b8d0d2a184b))
* Set Look action for selected WLED controllers ([#28](https://github.com/ketronkowski/light-show-controller/issues/28)) ([0f1a239](https://github.com/ketronkowski/light-show-controller/commit/0f1a239f9201cd2ce6eca5f18cefcfe243422063))
* show the app as Light Show Controller in the UI and docs ([#42](https://github.com/ketronkowski/light-show-controller/issues/42)) ([8a885ad](https://github.com/ketronkowski/light-show-controller/commit/8a885adb055d1c0121e817a247a724f4e7158fb8))


### Bug Fixes

* missing FPP schedule only warns before the first show night ([#35](https://github.com/ketronkowski/light-show-controller/issues/35)) ([7b3ea79](https://github.com/ketronkowski/light-show-controller/commit/7b3ea79749233a2a9d53f9d5f9e05eca38e46568))
* pair WLED controllers by their configured IP, not only by name ([#32](https://github.com/ketronkowski/light-show-controller/issues/32)) ([aafecc9](https://github.com/ketronkowski/light-show-controller/commit/aafecc9a10fb78997deca637ff21e598f99edc5d))
* pre-show check warns only when a board is lit, not just off its boot preset ([#38](https://github.com/ketronkowski/light-show-controller/issues/38)) ([da692b3](https://github.com/ketronkowski/light-show-controller/commit/da692b3d9611e124070166dc73c49dbc74949ecc))

## [1.0.0](https://github.com/ketronkowski/xlights-wled-integration/compare/v0.2.0...v1.0.0) (2026-11-13)


### Features

* add progress modal for bulk controller operations ([#9](https://github.com/ketronkowski/xlights-wled-integration/issues/9)) ([26e74be](https://github.com/ketronkowski/xlights-wled-integration/commit/26e74be7c5d4331a54de34943388e4aa00e7e047))

## [0.2.0](https://github.com/ketronkowski/xlights-wled-integration/compare/v0.1.0...v0.2.0) (2026-11-13)


### Features

* add Clear All Controllers destructive reset ([#8](https://github.com/ketronkowski/xlights-wled-integration/issues/8)) ([1ca5ae0](https://github.com/ketronkowski/xlights-wled-integration/commit/1ca5ae08b6c97a02e0460dbd574573d224a104f1))
* add phone boundary finder for measuring null pixels ([#15](https://github.com/ketronkowski/xlights-wled-integration/issues/15)) ([912ccae](https://github.com/ketronkowski/xlights-wled-integration/commit/912ccaea770692f8236ea93bd298bbdd068b76a6))
* add PR preview environments ([#11](https://github.com/ketronkowski/xlights-wled-integration/issues/11)) ([dc843b3](https://github.com/ketronkowski/xlights-wled-integration/commit/dc843b3b06d225b8d8eb35a4330f3281f885c899))
* add Technitium DNS sync script for Light Show controllers ([#16](https://github.com/ketronkowski/xlights-wled-integration/issues/16)) ([1e9904d](https://github.com/ketronkowski/xlights-wled-integration/commit/1e9904d39153f21e9ce664d7621d7c87ae204e6d))
* link controller addresses to their WLED UI ([#20](https://github.com/ketronkowski/xlights-wled-integration/issues/20)) ([529109b](https://github.com/ketronkowski/xlights-wled-integration/commit/529109b84dd40f9a8e9c261c625dc47119913332))
* treat FPP as a show controller with an access check ([#21](https://github.com/ketronkowski/xlights-wled-integration/issues/21)) ([c89024c](https://github.com/ketronkowski/xlights-wled-integration/commit/c89024c648d28a45f9ac6cbc6b481186ff8bbe67))


### Bug Fixes

* make Update Selected converge on WLED segments ([#18](https://github.com/ketronkowski/xlights-wled-integration/issues/18)) ([e046221](https://github.com/ketronkowski/xlights-wled-integration/commit/e0462217764ecb54e0bf2b999581def561e51aac))
* read custom model node count from the node field ([#19](https://github.com/ketronkowski/xlights-wled-integration/issues/19)) ([a40a0c1](https://github.com/ketronkowski/xlights-wled-integration/commit/a40a0c197ed8d5f61b983ac649cc611a5ec529ad))
* reload picks up new UI builds, and FPP no longer drops the connection ([#22](https://github.com/ketronkowski/xlights-wled-integration/issues/22)) ([1a6dcac](https://github.com/ketronkowski/xlights-wled-integration/commit/1a6dcac7f8e6c5137514500c535bb52cc26b009e))
* restore folder label from persisted upload status on page load ([#10](https://github.com/ketronkowski/xlights-wled-integration/issues/10)) ([bab4233](https://github.com/ketronkowski/xlights-wled-integration/commit/bab42337a7a9df0ee15d82529db3df3cde434424))
* **show-dns:** rename front-floods to front-far-left ([#17](https://github.com/ketronkowski/xlights-wled-integration/issues/17)) ([9f7ae93](https://github.com/ketronkowski/xlights-wled-integration/commit/9f7ae93cd56c1c0eccdc0af3f90aeb416963a4a5))
* tag preview images per-commit so pushes actually redeploy ([#14](https://github.com/ketronkowski/xlights-wled-integration/issues/14)) ([a9f4760](https://github.com/ketronkowski/xlights-wled-integration/commit/a9f476089273ea2d6a4f104c9ab4a8d7fdc89899))
* use relative API paths so the app works under a URL path prefix ([#13](https://github.com/ketronkowski/xlights-wled-integration/issues/13)) ([beaafab](https://github.com/ketronkowski/xlights-wled-integration/commit/beaafabd8dde578a6b7c63df5e378e9ab3a6a378))
