# ponytail: disposable build env — nix-shell this to get JDK 21 + Android SDK
# without polluting the host.
{ pkgs ? import <nixpkgs> { config = { allowUnfree = true; android_sdk.accept_license = true; }; } }:
let
  androidSdk = pkgs.androidenv.androidPkgs.androidsdk;
  sdkSrc = "${androidSdk}/libexec/android-sdk";
  sdkRoot = "/Users/briandavis/.cache/silo-android-sdk";
in
pkgs.mkShell {
  packages = [ pkgs.jdk21 androidSdk ];
  ANDROID_SDK_ROOT = sdkRoot;
  ANDROID_HOME = sdkRoot;
  JAVA_HOME = pkgs.jdk21.home;
  shellHook = ''
    # AGP wants build-tools 35.0.0 but nix ships 37.0.0. The nix store is
    # read-only, so AGP can't auto-install. Symlink the nix SDK into a
    # writable scratch dir so AGP can fill gaps without touching the host.
    rm -rf "${sdkRoot}"
    mkdir -p "${sdkRoot}/build-tools" "${sdkRoot}/licenses" "${sdkRoot}/ndk"
    for d in "${sdkSrc}"/*; do
      [ "$(basename "$d")" = "build-tools" ] && continue
      [ "$(basename "$d")" = "licenses" ] && continue
      [ "$(basename "$d")" = "ndk-bundle" ] && continue
      ln -sf "$d" "${sdkRoot}/$(basename "$d")"
    done
    for d in "${sdkSrc}/build-tools"/*; do
      ln -sf "$d" "${sdkRoot}/build-tools/$(basename "$d")"
    done
    for d in "${sdkSrc}/licenses"/*; do
      cp "$d" "${sdkRoot}/licenses/$(basename "$d")"
    done
  '';
}
