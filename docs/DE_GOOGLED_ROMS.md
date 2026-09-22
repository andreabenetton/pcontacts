# De-Googled Android ROMs

A de-Googled Android ROM is an Android-derived operating system that can be used without Google's proprietary Play Services and Google app stack having privileged access to the device.

Projects take different approaches: some remove Google components entirely, some replace parts of Google Play Services with microG, and others let Google Play Services be installed optionally without privileged system access.

Being de-Googled is distinct from the security of the operating system. Verified Boot, bootloader locking, security updates, hardware support and app sandboxing must be judged separately. The projects below are not ranked and do not offer equivalent guarantees.

## GrapheneOS

- **Google-free by default:** Yes
- **Google compatibility:** Optional official Google Play, installed as ordinary sandboxed apps without privileged system access.
- **Focus:** Strong security and privacy hardening.
- **Hardware:** Primarily supported Google Pixel devices.

> Does not use microG: when you choose to install them, the real Google Play components run inside the normal app sandbox.

Official website: <https://grapheneos.org/>

## CalyxOS

- **Google-free by default:** Yes
- **Google compatibility:** Optional microG.
- **Focus:** Privacy with relatively high app compatibility and usability.
- **Hardware:** Several device families, including Pixels and selected Fairphone, Motorola and SHIFT devices; the list changes over time.

Official website: <https://calyxos.org/>

## iodéOS

- **Google-free by default:** Yes
- **Google compatibility:** Uses microG.
- **Focus:** Privacy, tracker blocking and network-level visibility and control.
- **Hardware:** A range of devices from several manufacturers.

Official website: <https://iode.tech/>

## /e/OS

- **Google-free by default:** Yes
- **Google compatibility:** Uses microG for apps that depend on Google services.
- **Focus:** Consumer-friendly de-Googled ecosystem with replacement apps and services.
- **Hardware:** Broad device support; Android versions and security-support status vary between devices.

Official website: <https://e.foundation/>

## LineageOS for microG

- **Google-free by default:** Yes
- **Google compatibility:** microG is integrated into the distribution.
- **Focus:** LineageOS combined with a free and open-source Google-services compatibility layer.
- **Hardware:** Broad device support inherited from LineageOS, limited to the devices this project actually builds for.

> A distinct project from standard LineageOS.

Official website: <https://lineage.microg.org/>

## LineageOS

- **Google-free by default:** Yes, when installed without a Google Apps package.
- **Google compatibility:** Google Apps are not required; users may install Google packages separately where supported.
- **Focus:** General-purpose open-source Android distribution with broad hardware support.
- **Hardware:** Very broad device coverage compared with most privacy-focused distributions.

> Not a privacy-focused distribution in the sense of GrapheneOS or the dedicated de-Googled projects; listed because it can run without Google's proprietary stack.

Official website: <https://lineageos.org/>

## ShiftOS-L

- **Google-free by default:** Yes
- **Google compatibility:** The Google-free variant of SHIFT's Android operating system.
- **Focus:** Google-free Android on supported SHIFT hardware.
- **Hardware:** SHIFTphones.

Official website: <https://www.shift.eco/shiftos/>

## Replicant

- **Google-free by default:** Yes
- **Google compatibility:** Google services are not its objective.
- **Focus:** Software freedom: replacing or avoiding proprietary Android components wherever possible.
- **Hardware:** Limited and largely older hardware compared with the other projects here.

> Listed for its importance as a strongly free-software Android project, not as a practical equivalent to current projects in device availability.

Official website: <https://replicant.us/>
