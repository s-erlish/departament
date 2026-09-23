namespace ServiceLib.Models.Dto;

public class UpdateResult
{
    public bool Success { get; set; }
    public string? Msg { get; set; }

    public UpdateResult(bool success, string? msg)
    {
        Success = success;
        Msg = msg;
    }
}
