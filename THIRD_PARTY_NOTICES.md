# Third-Party Notices

## Better Endfield (upstream project)

- Source: https://github.com/Dr-hydra/Better-Endfield
- License: GNU Affero General Public License v3.0 (the same license this
  repository continues to use; see `LICENSE`)
- Relationship: this repository is a derivative of that project and is
  **independently maintained**. It is not an official upstream version and does
  not represent the upstream author. The upstream code, documentation and design
  remain the work of their original authors — principally `Dr-hydra`, with every
  contributor recorded in the preserved upstream commit history.
- Used in: the repository as a whole. The upstream runtime, modules, UI,
  installer and documentation form the base of this work; the modifications
  introduced here are listed in `CHANGELOG.md` and described in `README.md`
  ("Upstream & Project Origin").

## EIEM (Importing Endfield MMD)

- Source: https://github.com/Sasye/EIEM
- License: GNU Affero General Public License v3.0 (the same license as this project)
- Used in: `native/modules/camera/free_camera_runtime.inc`, where the VMD camera
  sampling follows EIEM's `camera_player.h` (Bezier evaluation, Euler signs,
  180 degree basis, 0.07 scale and 5 degree FOV defaults) and `vmd_parser.h`
  (camera record layout).
