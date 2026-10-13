namespace ArMoney.Assistant.Evaluation;
public sealed record SampleResult(bool Answerable,bool CorrectChoice,bool LexicalHit,bool HybridHit,bool CitationAttempted,bool CitationValid,bool Failed);
public sealed record EvaluationMetrics(int Samples,int Answerable,decimal CorrectToolChoice,decimal LexicalRecallAt5,decimal HybridRecallAt5,decimal CitationValidity,bool MachineGatePassed)
{
    public static EvaluationMetrics Aggregate(IReadOnlyList<SampleResult> samples)
    {
        var answerable=samples.Count(s=>s.Answerable);var attempted=samples.Count(s=>s.CitationAttempted);
        var choices=samples.Count==0?0:(decimal)samples.Count(s=>s.CorrectChoice)/samples.Count;
        var lexical=answerable==0?0:(decimal)samples.Count(s=>s.Answerable && s.LexicalHit)/answerable;
        var hybrid=answerable==0?0:(decimal)samples.Count(s=>s.Answerable && s.HybridHit)/answerable;
        var citations=attempted==0?0:(decimal)samples.Count(s=>s.CitationAttempted && s.CitationValid)/attempted;
        return new(samples.Count,answerable,choices,lexical,hybrid,citations,choices>=0.95m && hybrid>=0.90m && citations==1m && samples.All(s=>!s.Failed));
    }
}
