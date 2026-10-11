import { Outlet } from 'react-router';
import { BookOpenCheck, Bot, Library, ShieldCheck } from 'lucide-react';
import { BackToHome, Logo } from '@/components/ui';

export function AuthLayout() {
  return (
    <div className="relative min-h-dvh overflow-hidden">
      <div className="shelves opacity-60" aria-hidden />
      <div className="relative mx-auto grid min-h-dvh max-w-7xl lg:grid-cols-[1fr_1.05fr]">
        <aside className="relative hidden flex-col justify-between p-10 lg:flex">
          <Logo size="lg" />
          <div>
            <p className="eyebrow">Multi-Library Management & Digital Learning</p>
            <h2 className="mt-3 text-4xl font-extrabold leading-tight tracking-tight">More Books. More Knowledge.<br /><span className="text-gradient">A Smarter You.</span></h2>
            <ul className="mt-8 space-y-4 text-sm text-muted">
              <li className="flex items-center gap-3"><Library className="size-5 text-accent-2" aria-hidden />Search every library at once, borrow from the one you choose</li>
              <li className="flex items-center gap-3"><BookOpenCheck className="size-5 text-accent-2" aria-hidden />Track requests, due dates, fines and payments in one place</li>
              <li className="flex items-center gap-3"><Bot className="size-5 text-accent-2" aria-hidden />Ask the Gemini-powered assistant about books and libraries</li>
              <li className="flex items-center gap-3"><ShieldCheck className="size-5 text-accent-2" aria-hidden />Verified accounts and secure, role-based access</li>
            </ul>
          </div>
          <p className="font-hand text-2xl text-accent-2/80">Good books build better futures</p>
        </aside>
        <main id="main" className="flex flex-col px-4 py-6 sm:px-8 lg:py-10">
          <div className="mb-6 flex items-center justify-between lg:justify-end">
            <span className="lg:hidden"><Logo size="sm" /></span>
            <BackToHome />
          </div>
          <div className="flex flex-1 items-center justify-center">
            <div className="w-full max-w-md"><Outlet /></div>
          </div>
        </main>
      </div>
    </div>
  );
}
