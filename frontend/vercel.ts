import { type VercelConfig } from '@vercel/config/v1';

const backend =
  process.env.VERCEL_ENV === 'production'
    ? 'https://nforce-onehr-production.up.railway.app'
    : 'https://nforce-onehr-staging.up.railway.app';

export const config: VercelConfig = {
  rewrites: [
    {
      source: '/api/:path*',
      destination: `${backend}/api/:path*`,
    },
  ],
  git: {
    deploymentEnabled: {
      '*': false,
      main: true,
      dev: true,
    },
  },
};
