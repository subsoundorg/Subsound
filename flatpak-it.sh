#!/usr/bin/env bash

set -ex

export FLATPAK_MANIFEST="io.github.subsoundorg.Subsound.yml"

appstreamcli validate src/main/resources/app/io.github.subsoundorg.Subsound.metainfo.xml

# flatpak install flathub -y org.flatpak.Builder
flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest ${FLATPAK_MANIFEST}
flatpak run --command=flatpak-builder-lint org.flatpak.Builder appstream src/main/resources/app/io.github.subsoundorg.Subsound.metainfo.xml

flatpak-builder --force-clean --user --install-deps-from=flathub --repo=repo --install builddir ${FLATPAK_MANIFEST}


