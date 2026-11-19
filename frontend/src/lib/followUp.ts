const questionLimit = 1000;
const previousQuestionLabel = "Previous question context: ";
const nextQuestionLabel = "Follow-up question: ";

export function composeFollowUpQuestion(
  previousQuestion: string,
  nextQuestion: string,
) {
  const previous = previousQuestion.trim();
  const next = nextQuestion.trim();
  if (!previous || !next) return null;
  const combined = `${previousQuestionLabel}${previous}\n${nextQuestionLabel}${next}`;
  return Array.from(combined).length <= questionLimit ? combined : null;
}

export function followUpQuestionLimit(previousQuestion: string) {
  const previous = previousQuestion.trim();
  if (!previous) return questionLimit;
  const fixedLength = Array.from(
    `${previousQuestionLabel}${previous}\n${nextQuestionLabel}`,
  ).length;
  return Math.max(0, questionLimit - fixedLength);
}

export function limitCodePoints(value: string, limit: number) {
  return Array.from(value).slice(0, Math.max(0, limit)).join("");
}
