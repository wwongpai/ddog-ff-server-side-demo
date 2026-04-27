import type { Request, Response, NextFunction } from "express";

declare global {
  namespace Express {
    interface Request {
      ffContext: {
        targetingKey: string;
        city: string;
        country: string;
        tier: string;
        [key: string]: string;
      };
    }
  }
}

export function ffContextMiddleware(
  req: Request,
  _res: Response,
  next: NextFunction
) {
  req.ffContext = {
    targetingKey:
      (req.headers["x-user-id"] as string) ||
      req.body?.userId ||
      "anonymous",
    city: (req.headers["x-user-city"] as string) || "unknown",
    country: (req.headers["x-user-country"] as string) || "unknown",
    tier: (req.headers["x-user-tier"] as string) || "free",
  };
  next();
}
