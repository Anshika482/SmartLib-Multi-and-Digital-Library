import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery } from '@tanstack/react-query';
import { publicApi } from '@/api/endpoints';
import type { BookInput } from '@/api/types';
import { Field, Input, Select, Textarea } from '@/components/form';

const schema = z.object({
  title: z.string().trim().min(1, 'Title is required').max(300),
  subtitle: z.string().trim().max(300).optional(),
  authors: z.string().trim().min(1, 'Add at least one author'),
  categoryId: z.string().optional(),
  publisher: z.string().trim().max(200).optional(),
  publishedYear: z.string().regex(/^$|^\d{4}$/, 'Enter a 4-digit year').optional(),
  language: z.string().trim().max(40).optional(),
  pageCount: z.string().regex(/^$|^\d{1,5}$/, 'Enter a number').optional(),
  description: z.string().trim().max(5000).optional(),
  isbn: z.string().trim().max(20).optional(),
});
export type BookFormValues = z.infer<typeof schema>;

export function toBookInput(v: BookFormValues): BookInput {
  return {
    title: v.title.trim(), subtitle: v.subtitle || null,
    authors: v.authors.split(/[;,\n]/).map((a) => a.trim()).filter(Boolean),
    categoryId: v.categoryId ? Number(v.categoryId) : null, publisher: v.publisher || null,
    publishedYear: v.publishedYear ? Number(v.publishedYear) : null, language: v.language || null,
    pageCount: v.pageCount ? Number(v.pageCount) : null, description: v.description || null, isbn: v.isbn || null,
  };
}

/** Shared create/edit form for bibliographic data. */
export function BookForm({ id, defaults, onSubmit, isbnLocked, children }:
  { id: string; defaults: Partial<BookFormValues>; onSubmit: (v: BookFormValues, setError: (f: string, m: string) => void) => Promise<void>; isbnLocked?: boolean; children?: React.ReactNode }) {
  const categories = useQuery({ queryKey: ['public', 'categories'], queryFn: publicApi.categories });
  const { register, handleSubmit, reset, setError, formState: { errors } } = useForm<BookFormValues>({ resolver: zodResolver(schema), defaultValues: defaults });
  useEffect(() => reset(defaults), [defaults, reset]);
  return (
    <form id={id} noValidate className="grid gap-4 sm:grid-cols-2" onSubmit={handleSubmit((v) => onSubmit(v, (f, m) => setError(f as keyof BookFormValues, { message: m })))}>
      <Field label="Title" htmlFor={`${id}-title`} error={errors.title?.message} required className="sm:col-span-2"><Input id={`${id}-title`} invalid={!!errors.title} {...register('title')} /></Field>
      <Field label="Subtitle" htmlFor={`${id}-subtitle`} error={errors.subtitle?.message} className="sm:col-span-2"><Input id={`${id}-subtitle`} {...register('subtitle')} /></Field>
      <Field label="Authors" htmlFor={`${id}-authors`} error={errors.authors?.message} hint="Separate multiple authors with commas" required className="sm:col-span-2"><Input id={`${id}-authors`} invalid={!!errors.authors} {...register('authors')} /></Field>
      <Field label="ISBN" htmlFor={`${id}-isbn`} error={errors.isbn?.message} hint="ISBN-10 or ISBN-13; hyphens are fine"><Input id={`${id}-isbn`} readOnly={isbnLocked} {...register('isbn')} /></Field>
      <Field label="Category" htmlFor={`${id}-category`}>
        <Select id={`${id}-category`} {...register('categoryId')}>
          <option value="">Uncategorised</option>
          {categories.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </Select>
      </Field>
      <Field label="Publisher" htmlFor={`${id}-publisher`}><Input id={`${id}-publisher`} {...register('publisher')} /></Field>
      <Field label="Publication year" htmlFor={`${id}-year`} error={errors.publishedYear?.message}><Input id={`${id}-year`} inputMode="numeric" invalid={!!errors.publishedYear} {...register('publishedYear')} /></Field>
      <Field label="Language" htmlFor={`${id}-lang`}><Input id={`${id}-lang`} placeholder="English" {...register('language')} /></Field>
      <Field label="Pages" htmlFor={`${id}-pages`} error={errors.pageCount?.message}><Input id={`${id}-pages`} inputMode="numeric" invalid={!!errors.pageCount} {...register('pageCount')} /></Field>
      <Field label="Description" htmlFor={`${id}-desc`} className="sm:col-span-2"><Textarea id={`${id}-desc`} rows={4} {...register('description')} /></Field>
      {children}
    </form>
  );
}
