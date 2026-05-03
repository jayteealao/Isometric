// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// Allow overriding the base path for PR preview builds (e.g. SITE_BASE=/ for
// Cloudflare Pages, which uses its own domain and needs no subpath prefix).
const siteBase = process.env.SITE_BASE ?? '/Isometric';

export default defineConfig({
	site: 'https://jayteealao.github.io/Isometric',
	base: siteBase,
	integrations: [
		starlight({
			title: 'Isometric',
			description: 'Declarative isometric rendering for Jetpack Compose',
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/jayteealao/Isometric' },
			],
			sidebar: [
				{
					label: 'Getting Started',
					items: [
						{ label: 'Installation', slug: 'getting-started/installation' },
						{ label: 'Quick Start', slug: 'getting-started/quickstart' },
						{ label: 'Coordinate System', slug: 'getting-started/coordinate-system' },
					],
				},
				{
					label: 'Guides',
					items: [
						{ label: 'Shapes', slug: 'guides/shapes' },
						{ label: 'Stack', slug: 'guides/stack' },
						{ label: 'Tile Grid', slug: 'guides/tile-grid' },
						{ label: 'Animation', slug: 'guides/animation' },
						{ label: 'Gestures', slug: 'guides/gestures' },
						{ label: 'Per-Node Interactions', slug: 'guides/interactions' },
						{ label: 'Theming & Colors', slug: 'guides/theming' },
						{ label: 'Camera & Viewport', slug: 'guides/camera' },
						{ label: 'Custom Shapes', slug: 'guides/custom-shapes' },
						{ label: 'Performance', slug: 'guides/performance' },
						{ label: 'Compose Interop', slug: 'guides/compose-interop' },
						{ label: 'Advanced Config', slug: 'guides/advanced-config' },
					],
				},
				{
					label: 'Concepts',
					items: [
						{ label: 'Scene Graph', slug: 'concepts/scene-graph' },
						{ label: 'Depth Sorting', slug: 'concepts/depth-sorting' },
						{ label: 'Rendering Pipeline', slug: 'concepts/rendering-pipeline' },
					],
				},
				{
					label: 'Reference',
					items: [
						{ label: 'Composables', slug: 'reference/composables' },
						{ label: 'Scene Config', slug: 'reference/scene-config' },
						{ label: 'CompositionLocals', slug: 'reference/composition-locals' },
						{ label: 'Engine & Projector', slug: 'reference/engine' },
					],
				},
				{
					label: 'Examples',
					items: [
						{ label: 'Basic Scenes', slug: 'examples/basic-scenes' },
						{ label: 'Animation Patterns', slug: 'examples/animation-patterns' },
						{ label: 'Interactive Scenes', slug: 'examples/interactive-scenes' },
						{ label: 'Advanced Patterns', slug: 'examples/advanced-patterns' },
					],
				},
				{ label: 'FAQ', slug: 'faq' },
				{
					label: 'Migration',
					items: [
						{ label: 'View → Compose', slug: 'migration/view-to-compose' },
					],
				},
				{
					label: 'Contributing',
					items: [
						{ label: 'Development Setup', slug: 'contributing/setup' },
						{ label: 'Testing', slug: 'contributing/testing' },
						{ label: 'Documentation Guide', slug: 'contributing/docs-guide' },
					],
				},
				{
					label: 'API Reference',
					link: '/api/',
					attrs: { target: '_blank' },
				},
			],
			editLink: {
				baseUrl: 'https://github.com/jayteealao/Isometric/edit/master/site/src/content/docs/',
			},
			lastUpdated: true,
		}),
	],
});
