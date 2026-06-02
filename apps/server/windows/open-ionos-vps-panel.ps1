param(
    [string]$Url = 'https://cloudpanel.ionos.com/panel/corevps/servers/a4a63b41-8562-41b2-b011-748d58b8ad61?ionospanelid=ionosccf008bd-fef2-4a40-af34-d8f8a01bf2ea5105'
)

$ErrorActionPreference = 'Stop'
Start-Process $Url
