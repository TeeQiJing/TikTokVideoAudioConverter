; Inno Setup script for TikTok Video Audio Converter
; Build with:  ISCC.exe installer.iss
; Expects:
;   ..\dist\TikTokVideoAudioConverter.exe   (PyInstaller output)
;   ffmpeg\ffmpeg.exe, ffmpeg\ffprobe.exe   (bundled ffmpeg)

#define MyAppName "TikTok Video Audio Converter"
#define MyAppVersion "1.0.6"
#define MyAppPublisher "TeeQiJing"
#define MyAppURL "https://github.com/TeeQiJing/TikTokVideoAudioConverter"
#define MyAppExeName "TikTokVideoAudioConverter.exe"

[Setup]
AppId={{6E1E7A26-6B49-4A6C-9A70-2C6B6E5D9F41}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=Output
OutputBaseFilename=TikTokVideoAudioConverter-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\dist\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "bin\yt-dlp.exe"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "ffmpeg\ffmpeg.exe"; DestDir: "{app}\ffmpeg"; Flags: ignoreversion
Source: "ffmpeg\ffprobe.exe"; DestDir: "{app}\ffmpeg"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent
