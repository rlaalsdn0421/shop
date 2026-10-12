import { LoginForm } from "@/components/LoginForm";

type SearchParams = Promise<{ [key: string]: string | string[] | undefined }>;

export default async function LoginPage({ searchParams }: { searchParams: SearchParams }) {
  const { reason } = await searchParams;
  return <LoginForm expired={reason === "expired"} />;
}
