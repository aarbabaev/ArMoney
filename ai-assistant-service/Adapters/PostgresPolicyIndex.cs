using Npgsql;
using ArMoney.Assistant.Application;
using ArMoney.Assistant.Domain;
namespace ArMoney.Assistant.Adapters;
public sealed class PostgresPolicyIndex(NpgsqlDataSource source, OpenAiEmbeddings? embeddings = null) : IPolicyIndex
{
    public async Task<bool> Ready(CancellationToken token)
    {
        await using var command = source.CreateCommand(embeddings is null
            ? "SELECT count(*) > 0 FROM policy_documents d JOIN active_index a ON a.index_version=d.index_version WHERE effective_from<=now() AND (effective_to IS NULL OR effective_to>now())"
            : "SELECT count(*) > 0 AND bool_and(c.embedding IS NOT NULL AND c.embedding_model='text-embedding-3-small') FROM policy_documents d JOIN active_index a ON a.index_version=d.index_version JOIN policy_chunks c USING(document_id,version) WHERE effective_from<=now() AND (effective_to IS NULL OR effective_to>now())");
        return (bool)(await command.ExecuteScalarAsync(token) ?? false);
    }
    public async Task<IReadOnlyList<Chunk>> Search(string query,TurnBudget budget, CancellationToken token)
    {
        var vector=embeddings is null?null:await embeddings.Embed(AssistantTurn.SanitizeQuery(query),budget,token);
        await using var command = source.CreateCommand(vector is null ? """
            SELECT d.document_id,d.version,c.section,c.content,d.provenance
            FROM policy_chunks c JOIN policy_documents d USING(document_id,version)
            JOIN active_index a ON a.index_version=d.index_version
            WHERE d.effective_from<=now() AND (d.effective_to IS NULL OR d.effective_to>now())
              AND c.search @@ websearch_to_tsquery('simple',@query)
            ORDER BY ts_rank_cd(c.search,websearch_to_tsquery('simple',@query)) DESC,d.document_id,d.version,c.ordinal LIMIT 6
            """ : """
            WITH eligible AS (
              SELECT d.document_id,d.version,c.ordinal,c.section,c.content,d.provenance,c.search,c.embedding
              FROM policy_chunks c JOIN policy_documents d USING(document_id,version)
              JOIN active_index a ON a.index_version=d.index_version
              WHERE d.effective_from<=now() AND (d.effective_to IS NULL OR d.effective_to>now())
            ), lexical AS (
              SELECT document_id,version,ordinal,row_number() OVER(ORDER BY ts_rank_cd(search,websearch_to_tsquery('simple',@query)) DESC,document_id,version,ordinal) AS rank
              FROM eligible WHERE search @@ websearch_to_tsquery('simple',@query)
            ), semantic AS (
              SELECT document_id,version,ordinal,row_number() OVER(ORDER BY embedding <=> CAST(@vector AS vector),document_id,version,ordinal) AS rank
              FROM eligible WHERE embedding IS NOT NULL
            ), scores AS (
              SELECT document_id,version,ordinal,1.0/(60+rank) AS score FROM lexical WHERE rank<=20
              UNION ALL SELECT document_id,version,ordinal,1.0/(60+rank) FROM semantic WHERE rank<=20
            )
            SELECT e.document_id,e.version,e.section,e.content,e.provenance
            FROM eligible e JOIN (SELECT document_id,version,ordinal,sum(score) score FROM scores GROUP BY document_id,version,ordinal) s USING(document_id,version,ordinal)
            ORDER BY s.score DESC,e.document_id,e.version,e.ordinal LIMIT 6
            """);
        command.Parameters.AddWithValue("query", query);
        if(vector is not null) command.Parameters.AddWithValue("vector",vector);
        await using var reader = await command.ExecuteReaderAsync(token); var chunks = new List<Chunk>(); int chars = 0;
        while (await reader.ReadAsync(token))
        {
            var text = reader.GetString(3); if (chars + text.Length > 6000) break; chars += text.Length;
            chunks.Add(new(reader.GetString(0), reader.GetString(1), reader.GetString(2), text, reader.GetString(4)));
        }
        return chunks;
    }
}
