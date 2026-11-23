// Config plugin Expo: добавляет корневой сертификат GlobalSign Root R46 как дополнительный якорь доверия для vsuet.ru.
//
// Зачем: сайт университета подписан цепочкой, которая заканчивается корнем GlobalSign Root R46 (сервер присылает лист и один
// промежуточный сертификат). В хранилищах доверия части Android-устройств этого корня нет, и загрузка расписания падает с ошибкой
// проверки сертификата. Якорь действует ТОЛЬКО для vsuet.ru и его поддоменов; системные корни остаются доверенными.
// Файл сертификата: assets/certs/globalsign-root-r46.pem, отпечаток SHA-256
// 4F:A3:12:6D:8D:3A:11:D1:C4:85:5A:4F:80:7C:BA:D6:CF:91:9D:3A:5A:88:B0:3B:EA:2C:63:72:D9:3C:40:C9 (самоподписанный публичный корень).
const fs = require('fs');
const path = require('path');
const { withAndroidManifest, withDangerousMod } = require('@expo/config-plugins');

const CONFIG_NAME = 'network_security_config';
const CERT_RES_NAME = 'globalsign_root_r46';

const XML = `<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
  <domain-config>
    <domain includeSubdomains="true">vsuet.ru</domain>
    <trust-anchors>
      <certificates src="system" />
      <certificates src="@raw/${CERT_RES_NAME}" />
    </trust-anchors>
  </domain-config>
</network-security-config>
`;

module.exports = function withNetworkSecurityConfig(config) {
  config = withDangerousMod(config, [
    'android',
    (cfg) => {
      const res = path.join(cfg.modRequest.platformProjectRoot, 'app/src/main/res');
      fs.mkdirSync(path.join(res, 'xml'), { recursive: true });
      fs.mkdirSync(path.join(res, 'raw'), { recursive: true });
      fs.writeFileSync(path.join(res, 'xml', `${CONFIG_NAME}.xml`), XML);
      fs.copyFileSync(path.join(cfg.modRequest.projectRoot, 'assets/certs/globalsign-root-r46.pem'), path.join(res, 'raw', `${CERT_RES_NAME}.pem`));
      return cfg;
    },
  ]);
  return withAndroidManifest(config, (cfg) => {
    cfg.modResults.manifest.application[0].$['android:networkSecurityConfig'] = `@xml/${CONFIG_NAME}`;
    return cfg;
  });
};
