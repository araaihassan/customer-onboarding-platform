import type { Metadata } from "next";
import { Archivo, IBM_Plex_Mono } from "next/font/google";
import { ThemeProvider } from "@/components/ThemeProvider";
import "./globals.css";

/**
 * Archivo for human text, IBM Plex Mono for machine-generated values — ids, dates,
 * counts, metrics. That distinction is applied consistently across all nine
 * prototype screens and is one of the four design decisions that erode quietly, so
 * both families are loaded here and nothing else should introduce a third.
 *
 * The scaffold's Geist fonts are deliberately replaced rather than kept alongside.
 */
const archivo = Archivo({
  variable: "--font-archivo",
  subsets: ["latin"],
  display: "swap",
});

const plexMono = IBM_Plex_Mono({
  variable: "--font-plex-mono",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "Onboard OS",
  description: "Enterprise customer journey and onboarding platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    // suppressHydrationWarning is required by next-themes: it writes data-theme on
    // the client before React hydrates, so the server and client markup differ by
    // that one attribute by design.
    <html lang="en" suppressHydrationWarning>
      <body className={`${archivo.variable} ${plexMono.variable} antialiased`}>
        <ThemeProvider>{children}</ThemeProvider>
      </body>
    </html>
  );
}
