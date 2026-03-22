// @ts-check
const { themes: prismThemes } = require('prism-react-renderer');

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'zio-nats',
  tagline: 'A purely functional NATS client for ZIO',
  favicon: 'img/favicon.ico',
  url: 'https://pietersp.github.io',
  baseUrl: '/zio-nats/',
  organizationName: 'pietersp',
  projectName: 'zio-nats',
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',
  i18n: { defaultLocale: 'en', locales: ['en'] },
  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/pietersp/zio-nats/edit/master/docs/',
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
      }),
    ],
  ],
  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      navbar: {
        title: 'zio-nats',
        items: [
          {
            href: 'pathname:///api/index.html',
            label: 'API',
            position: 'left',
          },
          {
            href: 'https://github.com/pietersp/zio-nats',
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Links',
            items: [
              {
                label: 'GitHub',
                href: 'https://github.com/pietersp/zio-nats',
              },
              {
                label: 'Maven Central',
                href: 'https://central.sonatype.com/artifact/io.github.pietersp/zio-nats-core',
              },
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} zio-nats contributors. Built with Docusaurus.`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['java', 'scala', 'bash', 'hocon'],
      },
    }),
};

module.exports = config;
