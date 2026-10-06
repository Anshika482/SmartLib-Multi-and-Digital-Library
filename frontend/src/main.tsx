import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/tokens.css';
import './styles/global.css';

// The design system is imported once, here, before anything renders: tokens
// first, then the rules that use them.
const root = document.getElementById('root');

if (root === null) {
  throw new Error('index.html is missing the #root element the application mounts into.');
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
