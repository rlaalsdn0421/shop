export function TopBar({ title }: { title: string }) {
  return (
    <header className="sticky top-0 z-20 bg-white border-b border-black/10">
      <div className="max-w-md mx-auto h-12 flex items-center px-4">
        <h1 className="text-base font-extrabold tracking-tight">{title}</h1>
      </div>
    </header>
  );
}
