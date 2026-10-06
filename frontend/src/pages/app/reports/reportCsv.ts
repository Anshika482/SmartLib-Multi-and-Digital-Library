import { monthLabel, type Report } from '@/types/api';

/**
 * A report, as a CSV a spreadsheet will open.
 *
 * <p>Pure, and separate from the page, so what is exported can be checked
 * without rendering anything - an export that silently differs from the screen
 * is worse than no export.</p>
 *
 * <p><b>Nothing is recalculated here.</b> Every value written is one the server
 * sent; this only arranges them into rows.</p>
 */

/**
 * One CSV field.
 *
 * <p>Quotes anything containing a comma, a quote or a newline, and doubles
 * embedded quotes - the rule RFC 4180 states. A book title with a comma in it
 * is ordinary, and without this it would silently become two columns.</p>
 *
 * <p>A leading =, +, - or @ is prefixed with a quote as well. Spreadsheets
 * treat those as the start of a formula, so a title beginning with one would be
 * executed rather than displayed when the file is opened.</p>
 */
export function csvField(value: string | number): string {
  const text = String(value);
  const guarded = /^[=+\-@]/.test(text) ? `'${text}` : text;

  return /[",\n\r]/.test(guarded) ? `"${guarded.replace(/"/g, '""')}"` : guarded;
}

function row(...cells: (string | number)[]): string {
  return cells.map(csvField).join(',');
}

export function reportToCsv(report: Report): string {
  const totals = report.totals;

  const lines: string[] = [
    row('SmartLib report'),
    row('Scope', report.systemWide ? 'All libraries' : (report.libraryName ?? 'Library')),
    row('From', report.from),
    row('To', report.to),
    '',

    row('In this period'),
    row('Issues', totals.issues),
    row('Returns', totals.returns),
    row('Fines raised', totals.finesRaised.toFixed(2)),
    row('Fines paid', totals.finesPaid.toFixed(2)),
    row('Payments taken', totals.paymentsTaken.toFixed(2)),
    '',

    row('As things stand'),
    row('Titles held', totals.totalBooks),
    row('Members', totals.totalMembers),
    row('Out on loan', totals.activeLoans),
    row('Overdue', totals.overdueLoans),
    row('Still owed', totals.finesOutstanding.toFixed(2)),
    '',

    row('Borrowed most'),
    row('Title', 'Author', 'Issues'),
    ...report.mostIssued.map((book) => row(book.title, book.author, book.issues)),
    '',

    row('Shelves borrowed from'),
    row('Category', 'Issues'),
    ...report.categories.map((shelf) => row(shelf.category, shelf.issues)),
    '',

    row('Month by month'),
    row('Month', 'Issues', 'Returns', 'Fell overdue'),
  ];

  const overdueByMonth = new Map(
    report.overdueTrend.map((point) => [`${point.year}-${point.month}`, point.count]),
  );

  report.circulation.forEach((point) =>
    lines.push(
      row(
        monthLabel(point.year, point.month),
        point.issues,
        point.returns,
        overdueByMonth.get(`${point.year}-${point.month}`) ?? 0,
      ),
    ),
  );

  // Trailing newline: a file without one is a file some tools silently
  // truncate the last row of.
  return lines.join('\r\n') + '\r\n';
}
