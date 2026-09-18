import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";
import { CartProvider } from "@/cart/CartContext";
import { AuthProvider } from "@/auth/AuthContext";
import { TopBar } from "@/components/TopBar";
import { BottomTabBar } from "@/components/BottomTabBar";

export const metadata: Metadata = {
  title: "쇼핑몰 랭킹",
  description: "쇼핑몰 모바일 앱",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko" className="h-full antialiased">
      <body className="min-h-full bg-white text-black">
        <AuthProvider>
          <CartProvider>
            <div className="max-w-md mx-auto min-h-full bg-white">
              <TopBar title="쇼핑몰 랭킹" />
              <main className="pb-20">{children}</main>
              <BottomTabBar />
            </div>
          </CartProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
