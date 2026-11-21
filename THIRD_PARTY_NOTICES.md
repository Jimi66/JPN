# Third-party notices

## VK TURN proxy core

JPN 1.2.0 can package the Android ARM64 executable built from
[cacggghp/vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy), pinned to
commit `e8a96967dc66f3dbd631596ea6a8b9fe03f9be69`.

The upstream core is distributed under the GNU General Public License, version 3
(GPL-3.0). Its license text is included in the APK at
`assets/licenses/turn-core-GPL-3.0.txt`.

JPN-specific Android socket-protection changes are applied during the build by
`tools/turn-core-patch/patch_core.py`; the adapter source files are in the same
directory. The APK workflow checks out the pinned upstream source, applies those
patches, builds the ARM64 executable, and packages it under
`lib/arm64-v8a/libvkturn.so`.

To inspect the corresponding source, check out the pinned upstream commit and
apply the patcher from this repository. The build workflow
`.github/workflows/android.yml` records the exact build steps and toolchain.

Because the TURN core is GPL-3.0 software, redistribution of APKs containing it
must comply with GPL-3.0 obligations, including making the corresponding source
and applicable modifications available. This notice is informational and is
not legal advice; license compatibility for any wider JPN distribution should
be reviewed before release.
