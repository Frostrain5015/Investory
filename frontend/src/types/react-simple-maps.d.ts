declare module 'react-simple-maps' {
  import type { ComponentType, ReactNode } from 'react'

  /** 单个 GeoJSON 地理要素（react-simple-maps 解析地图后传给渲染回调）。 */
  export interface GeoFeature {
    rsmKey: string
    properties: Record<string, unknown>
    geometry: { type: string; coordinates: unknown }
    [key: string]: unknown
  }

  export interface ComposableMapProps {
    projection?: string
    projectionConfig?: { scale?: number; center?: [number, number] }
    style?: Record<string, string>
    children?: ReactNode
  }
  export const ComposableMap: ComponentType<ComposableMapProps>

  export interface GeographiesProps {
    geography: string
    children: (data: { geographies: GeoFeature[] }) => ReactNode
  }
  export const Geographies: ComponentType<GeographiesProps>

  export interface GeographyProps {
    geography: GeoFeature
    fill?: string
    stroke?: string
    strokeWidth?: number
    style?: Record<string, string | number>
    key?: string | number
  }
  export const Geography: ComponentType<GeographyProps>

  export interface MarkerProps {
    coordinates: [number, number]
    children?: ReactNode
  }
  export const Marker: ComponentType<MarkerProps>
}
