namespace v2rayN.Desktop.Account;

/// <summary>
/// Lightweight stand-in for Kotlin's Result&lt;T&gt;: the repository returns this instead of throwing,
/// so callers pattern on <see cref="IsSuccess"/> / <see cref="Error"/>. Failures always carry an
/// <see cref="ApiError"/>.
/// </summary>
public sealed class ApiResult<T>
{
    public bool IsSuccess { get; }
    public T? Value { get; }
    public ApiError? Error { get; }

    private ApiResult(bool isSuccess, T? value, ApiError? error)
    {
        IsSuccess = isSuccess;
        Value = value;
        Error = error;
    }

    public bool IsFailure => !IsSuccess;

    public static ApiResult<T> Success(T value) => new(true, value, null);

    public static ApiResult<T> Failure(ApiError error) => new(false, default, error);

    /// <summary>The value on success, otherwise default (null for reference types).</summary>
    public T? GetOrNull() => IsSuccess ? Value : default;

    /// <summary>The error on failure, otherwise null.</summary>
    public ApiError? ExceptionOrNull() => IsSuccess ? null : Error;

    public ApiResult<T> OnSuccess(Action<T> action)
    {
        if (IsSuccess && Value is not null)
        {
            action(Value);
        }
        return this;
    }

    public ApiResult<T> OnFailure(Action<ApiError> action)
    {
        if (IsFailure && Error is not null)
        {
            action(Error);
        }
        return this;
    }
}
